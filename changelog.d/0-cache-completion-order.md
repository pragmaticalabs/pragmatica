### Fixed
- Await cache-aside population and write-around invalidation before completing intercepted calls, preventing a subsequent sequential operation from overtaking cache maintenance. Cache backend failures retain fail-open behavior.
