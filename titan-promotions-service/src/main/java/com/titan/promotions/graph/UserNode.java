package com.titan.promotions.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

@Node
@Data
public class UserNode {
    @Id @GeneratedValue
    private Long id;
    private Long accountId;
    private String tier;
    
    @Relationship(type = "REFERRED", direction = Relationship.Direction.OUTGOING)
    private Set<UserNode> referrals = new HashSet<>();
}
