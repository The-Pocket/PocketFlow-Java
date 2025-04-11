# PocketFlow Java

A minimalist LLM framework, ported from Python to Java.

## Overview

PocketFlow Java is a direct port of the original [Python PocketFlow](https://github.com/The-Pocket/PocketFlow) framework. It provides a lightweight, flexible system for building and executing LLM-based workflows through a simple node-based architecture.

> **Note:** This is an initial implementation that currently does not support asynchronous operations. Community contributors are welcome to help enhance and maintain this project.

## Installation

### Maven

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.the-pocket</groupId>
    <artifactId>PocketFlow</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

**Groovy DSL (`build.gradle`):**
```groovy
dependencies {
    implementation 'io.github.the-pocket:PocketFlow:1.0.0'
}
```

**Kotlin DSL (`build.gradle.kts`):**
```kotlin
dependencies {
    implementation("io.github.the-pocket:PocketFlow:1.0.0")
}
```

## Usage

Here's a simple example of how to use PocketFlow Java in your application:

```java
import io.github.the_pocket.PocketFlow;
import io.github.the_pocket.PocketFlow.*;
import java.util.HashMap;
import java.util.Map;

public class MyWorkflowApp {

    // Define a custom start node
    static class MyStartNode extends Node<Void, String, String> {
        @Override
        public String exec(Void prepResult) {
            System.out.println("Starting workflow...");
            return "started";
        }
    }

    // Define a custom end node
    static class MyEndNode extends Node<String, Void, Void> {
        @Override
        public String prep(Map<String, Object> ctx) {
            return "Preparing to end workflow"; 
        }
        
        @Override
        public Void exec(String prepResult) {
            System.out.println("Ending workflow with: " + prepResult);
            return null;
        }
    }

    public static void main(String[] args) {
        // Create instances of your nodes
        MyStartNode startNode = new MyStartNode();
        MyEndNode endNode = new MyEndNode();

        // Connect the nodes
        startNode.next(endNode, "started");

        // Create a flow with the start node
        Flow<String> flow = new Flow<>(startNode);

        // Create a context and run the flow
        Map<String, Object> context = new HashMap<>();
        System.out.println("Executing workflow...");
        flow.run(context);
        System.out.println("Workflow completed successfully.");
    }
}
```

## Development

### Building the Project

```bash
mvn compile
```

### Running Tests

```bash
mvn test
```

## Contributing

Contributions are welcome! We're particularly looking for volunteers to:

1. Implement asynchronous operation support
2. Add comprehensive test coverage
3. Improve documentation and provide examples


Please feel free to submit pull requests or open issues for discussion.

## License

[MIT License](LICENSE) (or specify the actual license used)