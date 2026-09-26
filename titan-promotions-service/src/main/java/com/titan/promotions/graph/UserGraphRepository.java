package com.titan.promotions.graph;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserGraphRepository extends Neo4jRepository<UserNode, Long> {
    Optional<UserNode> findByAccountId(Long accountId);
    
    @Query("MATCH (u:UserNode {accountId: $accountId})-[:REFERRED*1..10]->(referral) RETURN referral")
    List<UserNode> findReferralChain(Long accountId);
    
    @Query("MATCH path = (u:UserNode {accountId: $accountId})<-[:REFERRED*]-(ancestor) " +
           "RETURN ancestor, length(path) as depth ORDER BY depth ASC LIMIT 10")
    List<UserNode> findAncestorChain(Long accountId);
}
