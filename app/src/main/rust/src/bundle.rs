//! A .typz bundle held in memory, serving as the Typst [`World`] it compiles in.

use std::collections::HashMap;
use std::fmt::Write;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

use typst::diag::{FileError, FileResult, SourceDiagnostic};
use typst::foundations::{Bytes, Datetime, Duration, NativeElement, StyleChain};
use typst::introspection::Introspector;
use typst::model::{Document, HeadingElem};
use typst::syntax::{FileId, RootedPath, Source, VirtualPath, VirtualRoot};
use typst::text::{Font, FontBook};
use typst::utils::LazyHash;
use typst::{Library, LibraryExt, World, WorldExt};
use typst_kit::datetime::Time;
use typst_kit::files::{FileLoader, FileStore};
use typst_kit::fonts::{self, FontStore};
use typst_layout::PagedDocument;

use crate::embedded_fonts;
use crate::packages::Packages;
use crate::text_layer;
use crate::viewer_inputs::{self, ViewerGeometry};

/// Typst's embedded fonts first (so its defaults resolve to them), then the device's fonts.
/// Scanning the system fonts takes a moment, so it happens once per process.
static FONTS: LazyLock<FontStore> = LazyLock::new(|| {
    let mut store = FontStore::new();
    store.extend(embedded_fonts::embedded());
    // fontdb's system font discovery is a no-op on Android, so scan the font directory directly.
    store.extend(fonts::scan(Path::new("/system/fonts")));
    store
});

/// Whether the fonts every bundle can use (Typst's own and the device's) include [`family`].
pub fn has_font_family(family: &str) -> bool {
    FONTS.book().select_family(&family.to_lowercase()).next().is_some()
}

pub struct Bundle {
    entrypoint: String,
    main: FileId,
    files: FileStore<BundleFiles>,
    /// Fonts only this bundle uses (e.g. downloaded for it), after the shared [`FONTS`].
    extra_fonts: Vec<Font>,
    /// [`FONTS`]'s book followed by [`Self::extra_fonts`]; None when there are none.
    font_book: Option<LazyHash<FontBook>>,
    /// How many fonts [`FONTS`] has, i.e. the index of the first extra font.
    shared_font_count: usize,
    time: Time,
    /// Carries `geometry` in `sys.inputs`; rebuilt when a compile offers different geometry.
    library: LazyHash<Library>,
    geometry: Option<ViewerGeometry>,
}

/// A compiled document: one SVG per page, plus warnings formatted like errors (empty if none).
pub struct Rendered {
    pub pages: Vec<RenderedPage>,
    pub warnings: String,
    /// The title set with `#set document(title: ..)`, as plain text, if any and not blank.
    pub title: Option<String>,
    /// The top-level sections: level 1 headings that an outline would list.
    pub sections: Vec<Section>,
}

/// A top-level section of a document: its name and where it starts.
pub struct Section {
    pub name: String,
    /// The index of its page.
    pub page: usize,
    /// How far down that page it starts, in points.
    pub y: f64,
}

/// A page as a standalone SVG, sized in points (`width="..pt"` with a matching viewBox), and
/// its [`text_layer`] as HTML to lay over it.
pub struct RenderedPage {
    pub svg: String,
    pub text_layer: String,
    pub width: f64,
    pub height: f64,
}

/// Why a compile produced no document.
pub enum CompileFailure {
    /// The formatted errors.
    Errors(String),
    /// Packages to download (see [`packages`]) before compiling again, e.g.
    /// `@preview/cetz:0.3.4`. Other errors may well be consequences of their absence.
    MissingPackages(Vec<String>),
}

/// The bundle's files, keyed by normalized path without a leading slash, and its packages.
struct BundleFiles {
    files: HashMap<String, Bytes>,
    packages: Packages,
}

impl FileLoader for BundleFiles {
    fn load(&self, id: FileId) -> FileResult<Bytes> {
        match id.root() {
            VirtualRoot::Project => {
                let path = id.vpath().get_without_slash();
                self.files.get(path).cloned().ok_or_else(|| FileError::NotFound(path.into()))
            }
            VirtualRoot::Package(spec) => {
                self.packages.load(spec, id.vpath(), |path| self.files.get(path).cloned())
            }
        }
    }
}

impl Bundle {
    /// `files` is keyed by the path inside the zip; `entrypoint` must be one of those paths.
    /// `package_cache` is where downloaded packages are kept; without it, only packages
    /// bundled under `packages/` are available. `fonts` are font files only this bundle can use,
    /// in addition to Typst's and the device's; ones that can't be read are skipped.
    pub fn new(
        entrypoint: String,
        files: HashMap<String, Vec<u8>>,
        package_cache: Option<PathBuf>,
        fonts: Vec<Vec<u8>>,
    ) -> Result<Self, String> {
        let mut normalized = HashMap::with_capacity(files.len());
        for (path, data) in files {
            // Typst can't address entries whose paths escape the root or are malformed; skip them.
            if let Ok(vpath) = VirtualPath::new(&path) {
                normalized.insert(vpath.get_without_slash().to_owned(), Bytes::new(data));
            }
        }
        let main_path = VirtualPath::new(&entrypoint)
            .map_err(|e| format!("Invalid entrypoint path {entrypoint}: {e:?}"))?;
        if !normalized.contains_key(main_path.get_without_slash()) {
            return Err(format!("Entrypoint {entrypoint} not found in the .typz file"));
        }
        let packages = Packages::new(normalized.keys().map(String::as_str), package_cache);
        let extra_fonts: Vec<Font> = fonts.into_iter().flat_map(|data| Font::iter(Bytes::new(data))).collect();
        let shared = FONTS.book();
        let shared_font_count = (0..).take_while(|&i| shared.info(i).is_some()).count();
        let font_book = (!extra_fonts.is_empty()).then(|| {
            let shared_infos = (0..shared_font_count).filter_map(|i| shared.info(i).cloned());
            let extra_infos = extra_fonts.iter().map(|font| font.info().clone());
            LazyHash::new(FontBook::from_infos(shared_infos.chain(extra_infos)))
        });
        Ok(Self {
            entrypoint,
            main: RootedPath::new(VirtualRoot::Project, main_path).intern(),
            files: FileStore::new(BundleFiles { files: normalized, packages }),
            extra_fonts,
            font_book,
            shared_font_count,
            time: Time::system(),
            library: LazyHash::new(Library::default()),
            geometry: None,
        })
    }

    pub fn entrypoint(&self) -> &str {
        &self.entrypoint
    }

    pub fn file_count(&self) -> usize {
        self.files.loader().files.len()
    }

    pub fn file_data(&self, path: &str) -> Option<&[u8]> {
        let vpath = VirtualPath::new(path).ok()?;
        self.files.loader().files.get(vpath.get_without_slash()).map(Bytes::as_slice)
    }

    /// Compiles the entrypoint, offering `geometry` to it through `sys.inputs` (see
    /// [`viewer_inputs`]), and renders each page to SVG.
    pub fn compile(&mut self, geometry: ViewerGeometry) -> Result<Rendered, CompileFailure> {
        if self.geometry != Some(geometry) {
            self.library = LazyHash::new(viewer_inputs::library(geometry));
            self.geometry = Some(geometry);
        }
        // Forget cached load results, so packages installed since the last compile are found.
        // (Sources are kept for reuse, so this doesn't cost incremental compilation.)
        self.files.reset();
        self.files.loader().packages.clear_missing();
        let result = typst::compile::<PagedDocument>(&*self);
        let document = result.output.map_err(|errors| {
            let missing = self.files.loader().packages.take_missing();
            if missing.is_empty() {
                CompileFailure::Errors(self.format_diagnostics(&errors))
            } else {
                CompileFailure::MissingPackages(missing)
            }
        })?;
        let options = typst_svg::SvgOptions::default();
        let pages = document
            .pages()
            .iter()
            .map(|page| {
                let size = page.frame.size();
                RenderedPage {
                    svg: typst_svg::svg(page, &options),
                    text_layer: text_layer::render(&page.frame),
                    width: size.x.to_pt(),
                    height: size.y.to_pt(),
                }
            })
            .collect();
        let title = document
            .info()
            .title
            .as_deref()
            .map(str::trim)
            .filter(|title| !title.is_empty())
            .map(str::to_owned);
        let introspector = document.introspector();
        let sections = introspector
            .query(&HeadingElem::ELEM.select())
            .iter()
            .filter_map(|content| {
                let heading = content.to_packed::<HeadingElem>()?;
                let top_level = heading.outlined.get(StyleChain::default())
                    && heading.resolve_level(StyleChain::default()).get() == 1;
                if !top_level {
                    return None;
                }
                let name = heading.body.plain_text().split_whitespace().collect::<Vec<_>>().join(" ");
                if name.is_empty() {
                    return None;
                }
                let position = introspector.position(content.location()?)?;
                Some(Section { name, page: position.page.get() - 1, y: position.point.y.to_pt() })
            })
            .collect();
        Ok(Rendered { pages, warnings: self.format_diagnostics(&result.warnings), title, sections })
    }

    /// Renders diagnostics as `path:line:col: message` lines, followed by any hints.
    fn format_diagnostics(&self, diagnostics: &[SourceDiagnostic]) -> String {
        let mut out = String::new();
        for diagnostic in diagnostics {
            if !out.is_empty() {
                out.push('\n');
            }
            if let Some(id) = diagnostic.span.id() {
                match id.root() {
                    VirtualRoot::Package(spec) => {
                        write!(out, "{spec}{}", id.vpath().get_with_slash())
                    }
                    VirtualRoot::Project => write!(out, "{}", id.vpath().get_without_slash()),
                }
                .ok();
                let position = self.range(diagnostic.span).zip(self.source(id).ok());
                if let Some((range, source)) = position {
                    let lines = source.lines();
                    if let Some((line, column)) =
                        lines.byte_to_line(range.start).zip(lines.byte_to_column(range.start))
                    {
                        write!(out, ":{}:{}", line + 1, column + 1).ok();
                    }
                }
                out.push_str(": ");
            }
            out.push_str(&diagnostic.message);
            for hint in &diagnostic.hints {
                write!(out, "\n  hint: {}", hint.v).ok();
            }
        }
        out
    }
}

impl World for Bundle {
    fn library(&self) -> &LazyHash<Library> {
        &self.library
    }

    fn book(&self) -> &LazyHash<FontBook> {
        self.font_book.as_ref().unwrap_or_else(|| FONTS.book())
    }

    fn main(&self) -> FileId {
        self.main
    }

    fn source(&self, id: FileId) -> FileResult<Source> {
        self.files.source(id)
    }

    fn file(&self, id: FileId) -> FileResult<Bytes> {
        self.files.file(id)
    }

    fn font(&self, index: usize) -> Option<Font> {
        match index.checked_sub(self.shared_font_count) {
            Some(extra) => self.extra_fonts.get(extra).cloned(),
            None => FONTS.font(index),
        }
    }

    fn today(&self, offset: Option<Duration>) -> Option<Datetime> {
        self.time.today(offset)
    }
}
