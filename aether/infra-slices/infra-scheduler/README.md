# Aether Infrastructure: Scheduler

Task scheduling service for the Aether distributed runtime.

## Overview

Provides task scheduling with fixed-rate, fixed-delay, and one-shot execution patterns. Supports named tasks with cancellation, task listing, and status checking.

## Usage

```java
var scheduler = Scheduler.scheduler();

// Fixed-rate: every 5 seconds
scheduler.scheduleAtFixedRate("metrics-collector",
    timeSpan(1).seconds(), timeSpan(5).seconds(),
    () -> { collectMetrics(); return Promise.unitPromise(); }
).await();

// Fixed-delay: 5 seconds after each completion
scheduler.scheduleWithFixedDelay("cleanup-job",
    timeSpan(0).seconds(), timeSpan(5).seconds(),
    () -> { cleanupOldData(); return Promise.unitPromise(); }
).await();

// One-shot: run once after 10 seconds
scheduler.schedule("send-reminder", timeSpan(10).seconds(),
    () -> { sendReminder(); return Promise.unitPromise(); }
).await();

// Management
scheduler.cancel("metrics-collector").await();
var tasks = scheduler.listTasks().await();
var isActive = scheduler.isScheduled("cleanup-job").await();
```

## Dependencies

- `pragmatica-lite-core`
