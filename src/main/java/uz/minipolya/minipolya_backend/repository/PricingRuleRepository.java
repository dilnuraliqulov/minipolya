package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.PricingRule;

import java.util.List;
import java.util.UUID;

@Repository
public interface PricingRuleRepository extends JpaRepository<PricingRule, UUID> {

    List<PricingRule> findByStadiumIdAndIsActiveTrue(UUID stadiumId);

    List<PricingRule> findByStadiumIdAndDayOfWeek(UUID stadiumId, Integer dayOfWeek);
}
