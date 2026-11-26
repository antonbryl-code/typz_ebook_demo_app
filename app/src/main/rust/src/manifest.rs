//! The `typst.toml` manifest at the root of a .typz file, which names the file to compile and
//! has a `[tool.typz-ebook]` section, required even if empty, with optional settings such as
//! the image of its cover:
//!
//! ```toml
//! [project]
//! entrypoint = "main.typ"
//!
//! [tool.typz-ebook]
//! cover-img = "images/cover.png"
//! required-fonts = ["Inter", "Noto Serif"]
//! ```

use toml::{Table, Value};
use typst::syntax::VirtualPath;

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

/// The name of the manifest's own section, under `[tool]`.
const SECTION: &str = "typz-ebook";

/// Checks that the manifest has a `[tool.typz-ebook]` section, which every .typz file must have.
pub fn check_section(manifest: &[u8]) -> Result<(), String> {
    let table = parse(manifest)?;
    match table.get("tool").and_then(Value::as_table).and_then(|tool| tool.get(SECTION)) {
        Some(Value::Table(_)) => Ok(()),
        Some(_) => Err(format!("tool.{SECTION} in typst.toml is not a section")),
        None => Err(format!("No [tool.{SECTION}] section in typst.toml")),
    }
}

/// The `cover-img` of the manifest's `[tool.typz-ebook]` section, as a normalized path from the
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

/// The `required-fonts` of the manifest's `[tool.typz-ebook]` section: font family names, as
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

/// A field of the `[tool.typz-ebook]` section, if there is one.
fn tool_field<'a>(table: &'a Table, name: &str) -> Option<&'a Value> {
    table
        .get("tool")
        .and_then(Value::as_table)
        .and_then(|tool| tool.get(SECTION))
        .and_then(Value::as_table)
        .and_then(|section| section.get(name))
}

fn parse(manifest: &[u8]) -> Result<Table, String> {
    let text = std::str::from_utf8(manifest).map_err(|_| "typst.toml is not valid UTF-8".to_owned())?;
    text.parse().map_err(|e| format!("Malformed typst.toml: {e}"))
}

#[cfg(test)]
mod tests {
    use super::{check_section, cover_img, entrypoint, required_fonts};

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
        assert!(check_section(b"[tool\n").is_err());
    }

    #[test]
    fn accepts_the_section_even_if_empty() {
        assert_eq!(check_section(b"[tool.typz-ebook]"), Ok(()));
        assert_eq!(check_section(b"[project]\nentrypoint = \"a.typ\"\n[tool.typz-ebook]\ncover-img = \"c.png\""), Ok(()));
        assert_eq!(check_section(b"tool.typz-ebook = {}"), Ok(()));
    }

    #[test]
    fn requires_the_section() {
        let missing = Err("No [tool.typz-ebook] section in typst.toml".to_owned());
        assert_eq!(check_section(b"[project]\nentrypoint = \"a.typ\""), missing);
        assert_eq!(check_section(b"[tool.typzy-reader]\ncover-img = \"c.png\""), missing);
        assert_eq!(check_section(b"tool = 1"), missing);
        assert!(check_section(b"[tool]\ntypz-ebook = 1").is_err());
    }

    #[test]
    fn reads_required_fonts() {
        let manifest = b"[tool.typz-ebook]\nrequired-fonts = [\"Inter\", \" Noto Serif \", 3, \"\", \"Inter\"]";
        assert_eq!(required_fonts(manifest), Ok(vec!["Inter".to_owned(), "Noto Serif".to_owned()]));
        assert_eq!(required_fonts(b"[project]\nentrypoint = \"a.typ\""), Ok(vec![]));
        assert_eq!(required_fonts(b"[tool.typz-ebook]\nrequired-fonts = \"Inter\""), Ok(vec![]));
    }

    #[test]
    fn normalizes_cover_img() {
        assert_eq!(cover_img(b"[tool.typz-ebook]\ncover-img = \"images/c.png\""), Ok(Some("images/c.png".into())));
        assert_eq!(cover_img(b"[tool.typz-ebook]\ncover-img = \"./images/../c.png\""), Ok(Some("c.png".into())));
    }

    #[test]
    fn has_no_cover_img_without_a_usable_path() {
        assert_eq!(cover_img(b"[project]\nentrypoint = \"a.typ\""), Ok(None));
        assert_eq!(cover_img(b"[tool.typz-ebook]"), Ok(None));
        assert_eq!(cover_img(b"[tool.typz-ebook]\ncover-img = 3"), Ok(None));
        assert_eq!(cover_img(b"[tool.typz-ebook]\ncover-img = \"\""), Ok(None));
        assert_eq!(cover_img(b"[tool.typz-ebook]\ncover-img = \"../c.png\""), Ok(None));
        assert!(cover_img(b"[tool\n").is_err());
    }
}
