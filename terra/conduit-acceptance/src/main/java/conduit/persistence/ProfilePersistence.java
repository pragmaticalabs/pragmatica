package conduit.persistence;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import conduit.domain.Profile;
import conduit.domain.UserId;
import conduit.domain.Username;


/// Profiles and the `follows` table. Follow and unfollow are idempotent: the composite primary key holds one row,
/// `ON CONFLICT DO NOTHING` absorbs a repeat or a concurrent duplicate (B-42).
@PgSql
public interface ProfilePersistence {
    /// An anonymous viewer is an empty `Option`, bound as NULL: it matches no follower, so `following` is false (B-47).
    @Query("""
        SELECT u.id, u.username, u.bio, u.image,
               EXISTS (SELECT 1 FROM follows f WHERE f.follower_id = :viewer AND f.followee_id = u.id) AS following
        FROM users u
        WHERE u.username = :username""")
    Promise<Option<Profile>> findProfile(Option<UserId> viewer, Username username);

    @Query("""
        INSERT INTO follows (follower_id, followee_id)
        VALUES (:followerId, :followeeId)
        ON CONFLICT DO NOTHING""")
    Promise<Unit> follow(UserId followerId, UserId followeeId);

    @Query("DELETE FROM follows WHERE follower_id = :followerId AND followee_id = :followeeId")
    Promise<Unit> unfollow(UserId followerId, UserId followeeId);
}
