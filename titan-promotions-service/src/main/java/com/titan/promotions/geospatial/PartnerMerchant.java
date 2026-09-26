package com.titan.promotions.geospatial;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;

@Entity
@Table(name = "partner_merchants")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerMerchant {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String name;
    
    @Column(columnDefinition = "geometry(Point,4326)")
    private Point location;
    
    @Column(nullable = false)
    private Integer radiusMeters;
    
    @Column(nullable = false)
    private BigDecimal discountPercentage;
    
    @Column(nullable = false)
    private String promoMessage;
}
