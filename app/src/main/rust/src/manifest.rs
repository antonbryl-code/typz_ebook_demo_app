//! The `typst.toml` manifest at the root of a .typz file, which names the file to compile and,
//! optionally, the format the document is meant to be read in:
//!
//! ```toml
//! [project]
//! entrypoint = "main.typ"
//!
//! [tool.typzy-reader]
//! format = "pageless"
//! ```

use toml::{Table, Value};

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
    let format = table
        .get("tool")
        .and_then(Value::as_table)
        .and_then(|tool| tool.get("typzy-reader"))
        .and_then(Value::as_table)
        .and_then(|section| section.get("format"));
    Ok(match format {
        None => DEFAULT_FORMAT.to_owned(),
        Some(Value::String(format)) => format.clone(),
        Some(other) => other.to_string(),
    })
}

fn parse(manifest: &[u8]) -> Result<Table, String> {
    let text = std::str::from_utf8(manifest).map_err(|_| "typst.toml is not valid UTF-8".to_owned())?;
    text.parse().map_err(|e| format!("Malformed typst.toml: {e}"))
}

#[cfg(test)]
mod tests {
    use super::{entrypoint, format};

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
}
