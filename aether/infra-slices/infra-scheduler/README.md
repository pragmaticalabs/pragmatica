# Aether Infrastructure: Scheduler

Task scheduling service for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-scheduler</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides task scheduling with fixed-rate, fixed-delay, and one-shot execution patterns.

### Key Features

- Fixed-rate scheduling (overlapping allowed)
- Fixed-delay scheduling (sequential execution)
- One-shot delayed execution
- Named tasks with cancellation support
- Task listing and status checking

### Quick Start

```java
var scheduler = Scheduler.scheduler();

// Fixed-rate: every 5 seconds, starting after 1 second
scheduler.scheduleAtFixedRate(
    "metrics-collector",
    timeSpan(1).seconds(),
    timeSpan(5).seconds(),
    () -> {
        collectMetrics();
        return Promise.unitPromise();
    }
).await();

// Fixed-delay: 5 seconds after each completion
scheduler.scheduleWithFixedDelay(
    "cleanup-job",
    timeSpan(0).seconds(),
    timeSpan(5).seconds(),
    () -> {
        cleanupOldData();
        return Promise.unitPromise();
    }
).await();

// One-shot: run once after 10 seconds
scheduler.schedule(
    "send-reminder",
    timeSpan(10).seconds(),
    () -> {
        sendReminder();
        return Promise.unitPromise();
    }
).await();

// Cancel a task
scheduler.cancel("metrics-collector").await();

// List active tasks
var tasks = scheduler.listTasks().await();

// Check if scheduled
var isActive = scheduler.isScheduled("cleanup-job").await();
```

### API Summary

| Method | Description |
|--------|-------------|
| `scheduleAtFixedRate(name, delay, period, task)` | Run at fixed intervals (may overlap) |
| `scheduleWithFixedDelay(name, delay, delay, task)` | Run with fixed gap between executions |
| `schedule(name, delay, task)` | One-shot execution after delay |
| `cancel(name)` | Cancel a scheduled task |
| `getTask(name)` | Get task handle |
| `listTasks()` | List all active tasks |
| `isScheduled(name)` | Check if task is active |

### ScheduledTaskHandle

```java
public interface ScheduledTaskHandle {
    String name();
    boolean isActive();
    Instant nextExecution();
    int executionCount();
}
```

## License

Apache License 2.0
