# Dependencies and the MapStruct/Lombok build

> Part of the MiniPolya CLAUDE.md doc set. **Read this before adding a dependency or touching the annotation processors.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Dependencies (pom.xml)

Required beyond Spring Initializr defaults:
- `org.liquibase:liquibase-core` — managed by Spring Boot (no version needed)
- `org.mapstruct:mapstruct:1.6.3` — MapStruct annotations
- `org.mapstruct:mapstruct-processor:1.6.3` — annotation processor (in `maven-compiler-plugin` `<annotationProcessorPaths>`)
- `org.projectlombok:lombok-mapstruct-binding:0.2.0` — required when using Lombok + MapStruct together
- `com.twelvemonkeys.imageio:imageio-webp:3.15.3` — a read-only ImageIO plugin for WebP stadium photos (see "Stadium photos")

## MapStruct + Lombok Maven Config

```xml
<properties>
    <mapstruct.version>1.6.3</mapstruct.version>
    <lombok-mapstruct-binding.version>0.2.0</lombok-mapstruct-binding.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>${mapstruct.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>${lombok.version}</version>
                    </path>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok-mapstruct-binding</artifactId>
                        <version>${lombok-mapstruct-binding.version}</version>
                    </path>
                    <path>
                        <groupId>org.mapstruct</groupId>
                        <artifactId>mapstruct-processor</artifactId>
                        <version>${mapstruct.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**Important**: Lombok must come BEFORE MapStruct in `<annotationProcessorPaths>` so Lombok generates getters/setters before MapStruct reads them.

