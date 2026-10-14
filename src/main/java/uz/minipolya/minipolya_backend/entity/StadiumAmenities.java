package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import org.hibernate.annotations.UuidGenerator;
import java.util.UUID;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Boolean feature flags for a stadium. Exactly one row per stadium.
 */
@Entity
@Table(name = "stadium_amenities")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StadiumAmenities {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stadium_id", nullable = false, unique = true)
    private Stadium stadium;

    @Builder.Default
    @Column(nullable = false)
    private boolean parking = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean lighting = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean showers = false;

    @Builder.Default
    @Column(name = "locker_room", nullable = false)
    private boolean lockerRoom = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean wifi = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean cafe = false;

    @Builder.Default
    @Column(name = "equipment_rental", nullable = false)
    private boolean equipmentRental = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean tribune = false;
}
