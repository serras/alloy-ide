# Spec Delta

## ADDED Requirements

### Requirement: Applying changes takes effect on the next analysis
When the user applies changed analyzer settings, every analysis started afterwards SHALL use the new values, with nothing restarted. An analysis that is already running SHALL finish with the values it started with.

#### Scenario: Apply with changes
- **WHEN** the user changes the skolem depth to 2, clicks Apply, and runs a command
- **THEN** the analyzer output of that run reports skolem depth 2

#### Scenario: Diagnostics unaffected
- **WHEN** the user applies changed settings while Alloy files are open
- **THEN** their diagnostics stay in place and are not recomputed from scratch

#### Scenario: Apply during an analysis
- **WHEN** settings are applied while a command is executing
- **THEN** that execution continues with its original settings, and its result is shown as outdated when it arrives

## REMOVED Requirements

### Requirement: Applying changes restarts the server
**Reason**: There is no language server to restart. Every analysis builds its analyzer options from the saved settings.
**Migration**: None needed. See "Applying changes takes effect on the next analysis".
