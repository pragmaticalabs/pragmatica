### Fixed (2026-10-04 — #1097: every JDK-backed HTTP provision leaked its HttpClient's selector thread)
- **`JdkHttpOperations` had no close**, so the `java.net.http.HttpClient` it wraps (a selector-manager
  thread plus a default executor since JDK 21) outlived every `@Http` resource, `@Notify(HTTP)` sender
  and `RemoteRepository` download that built one. It now implements `AsyncCloseable` and shuts the
  client down (non-blocking: in-flight requests complete, the selector thread then exits).
- **The owners now release it.** `HttpEmailSenderCore` (including the unknown-vendor sender, which
  still holds the operations its factory built) and `HttpNotificationSender` close what they hold, so
  `ResourceFactory`'s release dispatch reaches it; `RemoteRepository` closes its per-download client
  when the download settles. `@Http` already closed its operations through `JdkHttpClient.close`.
- Pinned by thread count (`HttpClient-N-SelectorManager` present while open, absent after) in
  `JdkHttpOperationsCloseTest`, `NotificationSenderFactoryHttpCloseTest` and
  `RemoteRepositoryCacheWiringTest`, plus close counting in `HttpEmailSenderCloseTest`.
