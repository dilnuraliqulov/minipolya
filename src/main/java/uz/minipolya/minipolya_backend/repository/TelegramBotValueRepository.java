package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.TelegramBotValue;

@Repository
public interface TelegramBotValueRepository extends JpaRepository<TelegramBotValue, String> {
}
