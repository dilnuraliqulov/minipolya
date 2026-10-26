package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;

import java.util.UUID;

/**
 * The platform_settings table holds exactly one row (guarded in the database),
 * so {@code findAll()} is how the service reaches it.
 */
@Repository
public interface PlatformSettingsRepository extends JpaRepository<PlatformSettings, UUID> {
}
