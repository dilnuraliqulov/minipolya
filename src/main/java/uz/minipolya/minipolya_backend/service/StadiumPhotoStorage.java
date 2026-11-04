package uz.minipolya.minipolya_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.util.ImageType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stadium photo files on the local filesystem, under {@code app.uploads.dir}:
 * {@code stadiums/<stadiumId>/<random uuid>.<jpg|png|webp>}, served back at
 * {@code /api/uploads/stadiums/<stadiumId>/<file>}. The client's file name is never used.
 *
 * <p>Files follow the database: a file written for a photo row is removed again if the
 * transaction rolls back, and a deleted photo's file goes only once the deletion commits.
 */
@Slf4j
@Component
public class StadiumPhotoStorage {

    /** The URL prefix of every stored photo; anything else (an old external URL) is not ours. */
    public static final String URL_PREFIX = "/api/uploads/stadiums/";

    private static final Pattern FILE_NAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)");
    private static final Pattern OWN_URL = Pattern.compile(
            Pattern.quote(URL_PREFIX) + "([0-9a-f-]{36})/(" + FILE_NAME.pattern() + ")");

    private final Path root;

    public StadiumPhotoStorage(AppProperties properties) {
        this.root = Path.of(properties.getUploads().getDir()).toAbsolutePath().normalize();
    }

    /** Writes the photo under a new random name and returns its URL. */
    public String store(UUID stadiumId, byte[] bytes, ImageType type) {
        String fileName = UUID.randomUUID() + "." + type.extension();
        Path dir = root.resolve("stadiums").resolve(stadiumId.toString());
        Path file = dir.resolve(fileName);
        try {
            Files.createDirectories(dir);
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not store the photo", ex);
        }
        afterRollback(() -> deleteQuietly(file));
        log.info("Stadium photo stored: stadiumId={} file={} bytes={}", stadiumId, fileName, bytes.length);
        return URL_PREFIX + stadiumId + "/" + fileName;
    }

    /** Removes the file behind a photo URL once the transaction commits; a URL not ours is left alone. */
    public void deleteAfterCommit(String url) {
        Optional<Path> file = pathOf(url);
        if (file.isEmpty()) {
            return;
        }
        Runnable delete = () -> deleteQuietly(file.get());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }

    /**
     * A stored photo file, or empty when the name is not one this storage gives out or no
     * such file exists. Only {@code <uuid>.<jpg|png|webp>} under a stadium id resolves, so no
     * other path (a directory, {@code ..}) can be reached.
     */
    public Optional<StoredFile> find(UUID stadiumId, String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            return Optional.empty();
        }
        Path file = root.resolve("stadiums").resolve(stadiumId.toString()).resolve(fileName).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
        return ImageType.fromExtension(extension).map(type -> new StoredFile(file, type));
    }

    /** A photo file on disk and the format its name says it is. */
    public record StoredFile(Path path, ImageType type) {
    }

    private Optional<Path> pathOf(String url) {
        if (url == null) {
            return Optional.empty();
        }
        Matcher m = OWN_URL.matcher(url);
        if (!m.matches()) {
            return Optional.empty();
        }
        Path file = root.resolve("stadiums").resolve(m.group(1)).resolve(m.group(2)).normalize();
        return file.startsWith(root) ? Optional.of(file) : Optional.empty();
    }

    private static void afterRollback(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    action.run();
                }
            }
        });
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            log.warn("Could not delete photo file {}: {}", file, ex.toString());
        }
    }
}
