package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.PricingRule;

import java.util.List;

@Repository
public interface PricingRuleRepository extends JpaRepository<PricingRule, Long> {

    List<PricingRule> findByStadiumIdAndIsActiveTrue(Long stadiumId);

    List<PricingRule> findByStadiumIdAndDayOfWeek(Long stadiumId, Integer dayOfWeek);
}
