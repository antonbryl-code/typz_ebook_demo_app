//! The `typst.toml` manifest at the root of a .typz file, which names the file to compile:
//!
//! ```toml
//! [project]
//! entrypoint = "main.typ"
//! ```

use toml::{Table, Value};

/// The `entrypoint` of the manifest's `[project]` section, as written there (a path relative
/// to the root of the .typz file).
pub fn entrypoint(manifest: &[u8]) -> Result<String, String> {
    let text = std::str::from_utf8(manifest).map_err(|_| "typst.toml is not valid UTF-8".to_owned())?;
    let table: Table = text.parse().map_err(|e| format!("Malformed typst.toml: {e}"))?;
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

#[cfg(test)]
mod tests {
    use super::entrypoint;

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
    }
}
