package com.titan.titancorebanking.repository;

import com.titan.titancorebanking.model.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    List<DeviceToken> findByUserId(Long userId);

    boolean existsByUserIdAndDeviceToken(Long userId, String deviceToken);
}