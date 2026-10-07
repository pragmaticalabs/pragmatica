### Fixed
- Inspect injected plain-step factory parameters when checking Terra's unsupported stream/entity resources. The shared plain-step model leaves its dependency list empty, which previously let these resources bypass Terra's compiler refusal. Aether generation is unchanged.
