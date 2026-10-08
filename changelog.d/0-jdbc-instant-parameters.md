### Fixed
- Normalize `Instant` JDBC parameters to `Timestamp` for queries, updates, and batches, including transactional resource calls. This enables unchanged ecommerce stock-reservation and shipment code on PostgreSQL JDBC.
