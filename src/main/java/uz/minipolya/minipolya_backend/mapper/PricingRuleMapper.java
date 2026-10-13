package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import uz.minipolya.minipolya_backend.dto.request.CreatePricingRuleRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdatePricingRuleRequest;
import uz.minipolya.minipolya_backend.dto.response.PricingRuleResponse;
import uz.minipolya.minipolya_backend.entity.PricingRule;

@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface PricingRuleMapper {

    @Mapping(source = "stadium.id", target = "stadiumId")
    PricingRuleResponse toResponse(PricingRule rule);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "stadium", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    PricingRule toEntity(CreatePricingRuleRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "stadium", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    void updateFromRequest(UpdatePricingRuleRequest request, @MappingTarget PricingRule rule);
}
