package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.text;


/// Shared declaration-header parsing for the naming / sealed-hierarchy rules
/// (JBCT-NAM-03, JBCT-SEAL-02).
///
/// A record/class `TypeKind` cursor's text carries the whole declaration; these helpers read the
/// header (everything before the body `{`) to recover the declared name and the simple head names
/// of the `implements` clause, with generics and package qualifiers stripped. Working from the
/// header keeps record-component commas (which precede `implements`) out of the type list.
///
/// Known FN: the header end is located at the first `{`, so a declaration annotation whose value
/// contains a brace (`@Ann({A.class, B.class}) record X() implements Y`) truncates the header
/// before `implements`, and the implemented names are missed. This is accepted as a rare shape;
/// the affected rules note it in their own FN surface.
sealed interface DeclSupport permits DeclSupport.unused {
    record unused() implements DeclSupport {}

    Pattern DECL_NAME = Pattern.compile("\\b(?:record|class|interface|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    Pattern IMPLEMENTS = Pattern.compile("\\bimplements\\b([\\s\\S]+)");

    /// Declared simple name of a record/class/interface/enum `TypeKind`, or `""` when absent.
    static String declName(Cursor decl) {
        var matcher = DECL_NAME.matcher(text(decl));

        return matcher.find()
               ? matcher.group(1)
               : "";
    }

    /// Simple head names of the declaration's `implements` clause (generics and package
    /// qualifiers removed), or an empty list when there is no `implements` clause.
    static List<String> implementedHeadNames(Cursor decl) {
        var declText = text(decl);
        var brace = declText.indexOf('{');
        var header = brace >= 0
                     ? declText.substring(0, brace)
                     : declText;
        var matcher = IMPLEMENTS.matcher(header);

        if (!matcher.find()) {
            return List.of();
        }

        return splitTopLevel(matcher.group(1));
    }

    private static List<String> splitTopLevel(String list) {
        var names = new ArrayList<String>();
        var current = new StringBuilder();
        var depth = 0;

        for (var i = 0; i < list.length(); i++) {
            var c = list.charAt(i);

            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                addHeadName(names, current.toString());
                current.setLength(0);

                continue;
            }

            if (depth == 0 && c != '>' && c != '<') {
                current.append(c);
            }
        }

        addHeadName(names, current.toString());

        return names;
    }

    private static void addHeadName(List<String> names, String entry) {
        var name = entry.trim();
        var dot = name.lastIndexOf('.');

        if (dot >= 0) {
            name = name.substring(dot + 1)
                       .trim();
        }

        if (!name.isEmpty()) {
            names.add(name);
        }
    }
}
