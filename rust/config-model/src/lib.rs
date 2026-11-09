//! Core configuration domain model, mirroring the Java `config-model` module.

/// The coordinates of one configuration set: an application in an environment.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct AppEnvironment {
    pub application: String,
    pub environment: String,
}

impl AppEnvironment {
    pub fn new(application: impl Into<String>, environment: impl Into<String>) -> Self {
        Self {
            application: application.into(),
            environment: environment.into(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_sets_both_coordinates() {
        let coordinates = AppEnvironment::new("billing", "prod");
        assert_eq!(coordinates.application, "billing");
        assert_eq!(coordinates.environment, "prod");
    }
}
