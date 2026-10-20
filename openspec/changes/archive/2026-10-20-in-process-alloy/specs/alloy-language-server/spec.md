# Spec Delta

## REMOVED Requirements

### Requirement: Server starts for Alloy files
**Reason**: The plugin no longer runs a language server. The Alloy analyzer runs inside the IDE.
**Migration**: None needed; see `alloy-semantics` and "Analysis runs inside the IDE" in `alloy-command-execution`.

### Requirement: Out-of-process isolation
**Reason**: The analyzer now runs in the IDE process by design.
**Migration**: "Analysis runs inside the IDE" in `alloy-command-execution` keeps the guarantee that no system Java is needed.

### Requirement: Server failures are reported
**Reason**: There is no server process that can fail.
**Migration**: Analyzer failures are reported per execution ("Failures reported inline" in `alloy-command-execution`) and degrade semantic features to name-based behavior ("Semantic features never block the editor" in `alloy-semantics`).

### Requirement: Diagnostics
**Reason**: Moved to the native semantics capability.
**Migration**: "Diagnostics" in `alloy-semantics`.

### Requirement: Hover documentation
**Reason**: Moved to the native semantics capability.
**Migration**: "Quick documentation" in `alloy-semantics`.

### Requirement: Go to definition
**Reason**: Moved to the native semantics capability.
**Migration**: "Go to declaration" in `alloy-semantics`.

### Requirement: Find usages
**Reason**: Moved to the native semantics capability.
**Migration**: "Find usages" in `alloy-semantics`.

### Requirement: Rename
**Reason**: Moved to the native semantics capability.
**Migration**: "Rename" in `alloy-semantics`.

### Requirement: Document symbols
**Reason**: Moved to the native semantics capability.
**Migration**: "Structure view and breadcrumbs" in `alloy-semantics`.

### Requirement: Workspace symbols
**Reason**: Moved to the native semantics capability.
**Migration**: "Go to Symbol" in `alloy-semantics`.

### Requirement: Single run UI
**Reason**: Without a server there are no server code lenses or command links to suppress.
**Migration**: None needed. The gutter icons from "Run gutter markers" in `alloy-command-execution` remain the only run affordance.

### Requirement: Supported IDEs
**Reason**: The plugin no longer requires the LSP module.
**Migration**: "Supported IDEs" in `alloy-editor`.
