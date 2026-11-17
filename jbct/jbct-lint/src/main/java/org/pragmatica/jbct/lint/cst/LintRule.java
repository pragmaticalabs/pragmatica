package org.pragmatica.jbct.lint.cst;

import java.util.Set;

import org.pragmatica.jbct.config.JbctMode;


/// One catalog entry of the JBCT lint rules, whichever way the rule runs (#2525).
///
/// A rule either looks at one file at a time ([CstLintRule]) or at the whole run once every file has been seen
/// ([RunRule]). The rule registry, the `[lint.rules]` overrides, the scoring categories and the mode filter all read
/// this one type, so a rule of either kind enters the same invariants.
public sealed interface LintRule permits CstLintRule, RunRule {
    /// The modes a rule runs in when it declares none: every code-level rule runs for business and infrastructure code.
    Set<JbctMode> CODE_MODES = Set.of(JbctMode.BUSINESS, JbctMode.INFRASTRUCTURE);
    /// The modes of a structure rule: it judges the layout of business code and stays silent for infrastructure code.
    Set<JbctMode> BUSINESS_ONLY = Set.of(JbctMode.BUSINESS);
    /// Get the rule ID (e.g., "JBCT-RET-01").
    String ruleId();

    /// The modes this rule runs in: a subset of {business, infrastructure} that contains business. `business` runs every
    /// rule, `infrastructure` the rules whose modes contain it, `format-only` and `disabled` none.
    default Set<JbctMode> modes() {
        return CODE_MODES;
    }

    /// Whether the linter's generic line-range suppression (`@SuppressWarnings`, `@Contract`,
    /// `@TerminalOperation`, `@NullReturn` on ANY enclosing declaration) applies to this rule's
    /// diagnostics. A rule that resolves its own, narrower exemption returns `false` — JBCT-EX-03
    /// accepts a mark only on the catch's nearest enclosing method (#1247), so a class-level,
    /// local-variable or parameter mark must not silence it.
    default boolean usesScopedSuppression() {
        return true;
    }
}
