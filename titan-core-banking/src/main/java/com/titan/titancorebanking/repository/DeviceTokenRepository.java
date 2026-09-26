package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    List<DeviceToken> findByUserId(Long userId);

    boolean existsByUserIdAndDeviceToken(Long userId, String deviceToken);

    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.user.id = :userId AND d.deviceToken = :token")
    void deleteByUserIdAndToken(Long userId, String token);
}
