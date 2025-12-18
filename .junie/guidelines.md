# Pragmatica Aether Development Guidelines

## Project Overview

Pragmatica Aether is a clusterized run-time environment for Java that aims to:

1. Transform traditional monolithic applications into distributed ones with benefits like scalability and fault
   tolerance
2. Provide a run-time environment for highly scalable distributed applications without the limitations of legacy
   microservices

## Architecture Guidelines

### Core Components

- **Consensus Protocol**: The project implements a custom consensus protocol (Rabia) for maintaining consistency across
  the cluster
- **Cluster Network**: Handles communication between nodes in the cluster
- **State Machine**: Manages the replicated state across the cluster

### Code Organization

- Package structure follows `org.pragmatica.<component>` pattern
- Core cluster functionality is in `org.pragmatica.cluster` package
- Consensus protocol implementation is in `org.pragmatica.cluster.consensus` package
- Network-related code is in `org.pragmatica.cluster.net` package

## Coding Standards

### General

- Use interfaces to define APIs and implementations to provide concrete functionality
- Follow Java naming conventions (camelCase for methods and variables, PascalCase for classes)
- Include JavaDoc comments for public APIs with `///` style comments
- Use functional programming patterns where appropriate (Promise, Option)

### Error Handling

- Use the Promise pattern for asynchronous operations
- Define specific error types in dedicated error classes (e.g., ConsensusErrors)
- Avoid throwing exceptions for expected failure scenarios

### Concurrency

- Use immutable data structures where possible
- Use atomic variables for shared mutable state
- Use explicit thread management with ExecutorService
- Use Promise for asynchronous processing

### Testing

- Write unit tests for all components
- Use test infrastructure to simulate cluster behavior
- Test both happy path and failure scenarios

## Development Workflow

### Adding New Features

1. Understand the existing architecture and how your feature fits in
2. Design your feature to be consistent with the existing patterns
3. Implement the feature with appropriate tests
4. Ensure backward compatibility with existing code

### Modifying Existing Code

1. Understand the purpose and design of the code you're modifying
2. Make minimal changes to achieve the desired outcome
3. Update tests to reflect your changes
4. Ensure you haven't broken existing functionality

### Code Review Guidelines

- Check for adherence to the project's architectural patterns
- Verify proper error handling and concurrency management
- Ensure adequate test coverage
- Look for potential performance issues in distributed operations

## Performance Considerations

- Be mindful of network communication overhead
- Consider the impact of your changes on consensus protocol performance
- Design for scalability across a large number of nodes
- Optimize critical paths in the consensus protocol

## Documentation

- Update documentation when making significant changes
- Document complex algorithms and data structures
- Include examples for API usage
- Document performance characteristics and limitations