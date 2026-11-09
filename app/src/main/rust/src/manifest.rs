//! The `typst.toml` manifest at the root of a .typz file, which names the file to compile and,
//! optionally, the format the document is meant to be read in and the image of its cover:
//!
//! ```toml
//! [project]
//! entrypoint = "main.typ"
//!
//! [tool.typzy-reader]
//! format = "pageless"
//! cover-img = "images/cover.png"
//! required-fonts = ["Inter", "Noto Serif"]
//! ```

use toml::{Table, Value};
use typst::syntax::VirtualPath;

/// The format of documents whose manifest doesn't name one.
const DEFAULT_FORMAT: &str = "pageless";

/// The `entrypoint` of the manifest's `[project]` section, as written there (a path relative
/// to the root of the .typz file).
pub fn entrypoint(manifest: &[u8]) -> Result<String, String> {
    let table = parse(manifest)?;
    let project = match table.get("project") {
        Some(Value::Table(project)) => project,
        Some(_) => return Err("project in typst.toml is not a section".into()),
        None => return Err("No [project] section in typst.toml".into()),
    };
    match project.get("entrypoint") {
        Some(Value::String(path)) if !path.trim().is_empty() => Ok(path.clone()),
        Some(Value::String(_)) => Err("The entrypoint in typst.toml is empty".into()),
        Some(_) => Err("The entrypoint in typst.toml is not a string".into()),
        None => Err("No entrypoint in the [project] section of typst.toml".into()),
    }
}

/// The `format` of the manifest's `[tool.typzy-reader]` section, [`DEFAULT_FORMAT`] if there is
/// none. Not checked against the known formats; a value that isn't a string comes back as TOML.
pub fn format(manifest: &[u8]) -> Result<String, String> {
    let table = parse(manifest)?;
    Ok(match tool_field(&table, "format") {
        None => DEFAULT_FORMAT.to_owned(),
        Some(Value::String(format)) => format.clone(),
        Some(other) => other.to_string(),
    })
}

/// The `cover-img` of the manifest's `[tool.typzy-reader]` section, as a normalized path from the
/// root of the .typz file. None if there is none, or if it can't be a path inside the .typz file:
/// a book with an unusable cover is still readable, and gets the default cover.
pub fn cover_img(manifest: &[u8]) -> Result<Option<String>, String> {
    let table = parse(manifest)?;
    Ok(tool_field(&table, "cover-img")
        .and_then(Value::as_str)
        .and_then(|path| VirtualPath::new(path).ok())
        .map(|path| path.get_without_slash().to_owned())
        .filter(|path| !path.is_empty()))
}

/// The `required-fonts` of the manifest's `[tool.typzy-reader]` section: font family names, as
/// written there (and so as Google Fonts spells them), without blanks or repeats. Values that
/// aren't strings are left out.
pub fn required_fonts(manifest: &[u8]) -> Result<Vec<String>, String> {
    let table = parse(manifest)?;
    let mut fonts: Vec<String> = Vec::new();
    for value in tool_field(&table, "required-fonts").and_then(Value::as_array).into_iter().flatten() {
        if let Some(name) = value.as_str().map(str::trim).filter(|name| !name.is_empty()) {
            if !fonts.iter().any(|font| font == name) {
                fonts.push(name.to_owned());
            }
        }
    }
    Ok(fonts)
}

/// A field of the `[tool.typzy-reader]` section, if there is one.
fn tool_field<'a>(table: &'a Table, name: &str) -> Option<&'a Value> {
    table
        .get("tool")
        .and_then(Value::as_table)
        .and_then(|tool| tool.get("typzy-reader"))
        .and_then(Value::as_table)
        .and_then(|section| section.get(name))
}

fn parse(manifest: &[u8]) -> Result<Table, String> {
    let text = std::str::from_utf8(manifest).map_err(|_| "typst.toml is not valid UTF-8".to_owned())?;
    text.parse().map_err(|e| format!("Malformed typst.toml: {e}"))
}

#[cfg(test)]
mod tests {
    use super::{cover_img, entrypoint, format, required_fonts};

    #[test]
    fn reads_project_entrypoint() {
        let manifest = "# A book\n[project]\nname = \"sample\"\nentrypoint = 'src/main.typ' # here\n";
        assert_eq!(entrypoint(manifest.as_bytes()), Ok("src/main.typ".into()));
    }

    #[test]
    fn accepts_other_spellings_of_the_same_table() {
        assert_eq!(entrypoint(b"project.entrypoint = \"a.typ\""), Ok("a.typ".into()));
        assert_eq!(entrypoint(b"project = { entrypoint = \"b.typ\" }"), Ok("b.typ".into()));
    }

    #[test]
    fn ignores_entrypoints_of_other_sections() {
        assert!(entrypoint(b"[package]\nentrypoint = \"lib.typ\"").is_err());
        assert!(entrypoint(b"entrypoint = \"main.typ\"\n[project]\n").is_err());
    }

    #[test]
    fn rejects_malformed_manifests() {
        assert!(entrypoint(b"[project\nentrypoint = \"main.typ\"").is_err());
        assert!(entrypoint(b"[project]\nentrypoint = 3").is_err());
        assert!(entrypoint(b"[project]\nentrypoint = \"  \"").is_err());
        assert!(entrypoint(b"project = 1").is_err());
        assert!(entrypoint(b"\xff").is_err());
        assert!(format(b"[tool\n").is_err());
    }

    #[test]
    fn reads_format() {
        assert_eq!(format(b"[tool.typzy-reader]\nformat = \"book\""), Ok("book".into()));
        assert_eq!(format(b"[tool.typzy-reader]\nformat = \"other\""), Ok("other".into()));
    }

    #[test]
    fn defaults_format_to_pageless() {
        assert_eq!(format(b"[project]\nentrypoint = \"a.typ\""), Ok("pageless".into()));
        assert_eq!(format(b"[tool.typzy-reader]\nother = 1"), Ok("pageless".into()));
        assert_eq!(format(b"[tool.other]\nformat = \"book\""), Ok("pageless".into()));
    }

    #[test]
    fn shows_other_format_values_as_toml() {
        assert_eq!(format(b"[tool.typzy-reader]\nformat = 3"), Ok("3".into()));
    }

    #[test]
    fn reads_required_fonts() {
        let manifest = b"[tool.typzy-reader]\nrequired-fonts = [\"Inter\", \" Noto Serif \", 3, \"\", \"Inter\"]";
        assert_eq!(required_fonts(manifest), Ok(vec!["Inter".to_owned(), "Noto Serif".to_owned()]));
        assert_eq!(required_fonts(b"[project]\nentrypoint = \"a.typ\""), Ok(vec![]));
        assert_eq!(required_fonts(b"[tool.typzy-reader]\nrequired-fonts = \"Inter\""), Ok(vec![]));
    }

    #[test]
    fn normalizes_cover_img() {
        assert_eq!(cover_img(b"[tool.typzy-reader]\ncover-img = \"images/c.png\""), Ok(Some("images/c.png".into())));
        assert_eq!(cover_img(b"[tool.typzy-reader]\ncover-img = \"./images/../c.png\""), Ok(Some("c.png".into())));
    }

    #[test]
    fn has_no_cover_img_without_a_usable_path() {
        assert_eq!(cover_img(b"[project]\nentrypoint = \"a.typ\""), Ok(None));
        assert_eq!(cover_img(b"[tool.typzy-reader]\nformat = \"book\""), Ok(None));
        assert_eq!(cover_img(b"[tool.typzy-reader]\ncover-img = 3"), Ok(None));
        assert_eq!(cover_img(b"[tool.typzy-reader]\ncover-img = \"\""), Ok(None));
        assert_eq!(cover_img(b"[tool.typzy-reader]\ncover-img = \"../c.png\""), Ok(None));
        assert!(cover_img(b"[tool\n").is_err());
    }
}
