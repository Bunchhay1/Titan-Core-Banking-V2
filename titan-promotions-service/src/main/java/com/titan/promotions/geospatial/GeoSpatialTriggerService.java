package com.titan.promotions.geospatial;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Repository
interface PartnerMerchantRepository extends JpaRepository<PartnerMerchant, Long> {
    @Query(value = "SELECT * FROM partner_merchants WHERE ST_DWithin(location, ST_SetSRID(ST_MakePoint(?1, ?2), 4326)::geography, radius_meters)", nativeQuery = true)
    List<PartnerMerchant> findNearbyMerchants(double longitude, double latitude);
}

@Service
@Slf4j
@RequiredArgsConstructor
public class GeoSpatialTriggerService {
    
    private final PartnerMerchantRepository merchantRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final GeometryFactory geometryFactory = new GeometryFactory();
    
    @KafkaListener(topics = "user-location-pings", groupId = "geo-triggers")
    public void processLocationPing(LocationPingEvent event) {
        List<PartnerMerchant> nearby = merchantRepository.findNearbyMerchants(event.longitude(), event.latitude());
        
        nearby.forEach(merchant -> {
            log.info("User {} within {}m of {}", event.accountId(), merchant.getRadiusMeters(), merchant.getName());
            
            var notification = new ProximityNotification(
                event.accountId(),
                merchant.getName(),
                merchant.getPromoMessage(),
                merchant.getDiscountPercentage()
            );
            
            kafkaTemplate.send("push-notifications", notification);
        });
    }
    
    public record LocationPingEvent(Long accountId, double latitude, double longitude) {}
    public record ProximityNotification(Long accountId, String merchantName, String message, BigDecimal discount) {}
}
