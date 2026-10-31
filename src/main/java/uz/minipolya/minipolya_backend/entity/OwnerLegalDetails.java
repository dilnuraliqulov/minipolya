package uz.minipolya.minipolya_backend.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

/**
 * A stadium owner's legal form, tax id and ATMOS contract — one row per user, kept by
 * admins. {@link #atmosRecipientId} is the only place the recipient of an owner's card
 * splits is stored; {@code PaymentService} reads it from here. It is set exactly when
 * the contract is signed.
 */
@Entity
@Table(name = "owner_legal_details")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerLegalDetails {

    /** The user's id: one row per user. */
    @Id
    @Column(name = "user_id")
    private UUID userId;

    /** Null only on a row moved from the old users column, until an admin fills it in. */
    @Convert(converter = LegalTypeConverter.class)
    @Column(name = "legal_type", length = 20)
    private LegalType legalType;

    /** STIR: 9 digits; PINFL, 14 digits, for the self-employed. */
    @Column(length = 14)
    private String tin;

    @Builder.Default
    @Convert(converter = ContractStatusConverter.class)
    @Column(name = "atmos_contract_status", nullable = false, length = 10)
    private ContractStatus atmosContractStatus = ContractStatus.NONE;

    @Column(name = "atmos_recipient_id", length = 64)
    private String atmosRecipientId;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** The admin's name as it was when they made the change. */
    @Column(name = "updated_by_name", length = 120)
    private String updatedByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Card payouts can go to this owner: the contract is signed and the recipient known. */
    public boolean canReceivePayouts() {
        return atmosContractStatus == ContractStatus.SIGNED && atmosRecipientId != null;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public enum LegalType {
        /** YaTT — an individual entrepreneur. */
        YATT,
        /** MChJ — a limited liability company. */
        MCHJ,
        /** Oʻzini oʻzi band qilgan — self-employed; their tax id is the 14-digit PINFL. */
        SELF_EMPLOYED;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static LegalType fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum ContractStatus {
        NONE, PENDING, SIGNED;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static ContractStatus fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    @Converter
    public static class LegalTypeConverter implements AttributeConverter<LegalType, String> {
        @Override
        public String convertToDatabaseColumn(LegalType attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public LegalType convertToEntityAttribute(String dbData) {
            return dbData == null ? null : LegalType.fromValue(dbData);
        }
    }

    @Converter
    public static class ContractStatusConverter implements AttributeConverter<ContractStatus, String> {
        @Override
        public String convertToDatabaseColumn(ContractStatus attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public ContractStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : ContractStatus.fromValue(dbData);
        }
    }
}
