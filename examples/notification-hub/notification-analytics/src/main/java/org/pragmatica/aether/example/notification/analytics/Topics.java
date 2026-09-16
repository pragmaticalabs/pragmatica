package org.pragmatica.aether.example.notification.analytics;

import org.pragmatica.aether.example.notification.DeliveryStatusEvent;
import org.pragmatica.aether.slice.topic.Topic;


/// The `delivery-status` topic constant (#396), declared LOCALLY in this module by necessity.
///
/// The slice processor resolves a `@ResourceQualifier(config = "DELIVERY_STATUS")` identifier against
/// the CURRENT COMPILATION ROUND's root elements and reads the name literal out of the constant's
/// source tree (`ResolvedTopicConstant.resolve` / `extractTopicName`). A constant living in a
/// dependency JAR is neither a root element nor has a source tree, so it cannot be found at all —
/// which is why this pair is duplicated in the emailer and the analytics module rather than shared
/// from `notification-service` the way `DeliveryStatusEvent` is. Only the PAYLOAD TYPE crosses the
/// module boundary; the name literal cannot.
///
/// That duplication is the point rather than a wart: two independently compiled slices each declare
/// the same bare topic, and they meet only because the address is namespaced by the BLUEPRINT that
/// owns them both (#1216) — never by either slice's own coordinates.
///
/// WHY THE DIRECTION IS EMAILER -> ANALYTICS AND NOT SERVICE -> ANYTHING. This blueprint's own
/// artifact is `notification-hub-notification-service`, so a topic published BY that slice derives
/// an identical namespace whether it is scoped to the blueprint or to the slice, and could not tell
/// correct addressing from broken. Publishing from the emailer to the analytics slice makes all
/// three coordinates distinct, so the bare name resolves only if it is genuinely blueprint-scoped.
public interface Topics {
    Topic<DeliveryStatusEvent> DELIVERY_STATUS = Topic.of("delivery-status", DeliveryStatusEvent.class);
}
