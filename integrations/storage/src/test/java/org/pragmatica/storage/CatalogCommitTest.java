package org.pragmatica.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.storage.CatalogFixture.NAMESPACE;
import static org.pragmatica.storage.CatalogFixture.entry;
import static org.pragmatica.storage.CatalogFixture.name;


/// The pure catalog core: one commit is one new root holding every mutation of it or none, and a commit rebuilt on a newer
/// root (the rebase) re-checks its preconditions there and commutes with commits on other keys.
class CatalogCommitTest {
    private static final int FANOUT = 3;

    private final CatalogFixture.Pages pages = new CatalogFixture.Pages();

    private CatalogDraft.CatalogWrite built(CatalogRoot root, CatalogMutation... mutations) {
        var write = CatalogCommit.catalogCommit(List.of(mutations)).onto(root, NAMESPACE, pages.source(), FANOUT).await().unwrap();

        pages.store(write.blocks());

        return write;
    }

    private CatalogRoot committed(CatalogRoot root, CatalogMutation... mutations) {
        return built(root, mutations).root();
    }

    private CatalogDraft draft(CatalogRoot root) {
        return CatalogDraft.catalogDraft(root, NAMESPACE, pages.source());
    }

    private Option<ObjectId> holder(CatalogRoot root, String value) {
        return draft(root).holder(name(value)).await().unwrap();
    }

    private Option<ObjectEntry> entryOf(CatalogRoot root, ObjectId id) {
        return draft(root).entry(id).await().unwrap();
    }

    /// Everything a root says: each object's name, size and content type, and each name's object.
    private Map<String, String> state(CatalogRoot root) {
        var state = new TreeMap<String, String>();

        PageTree.cells(root.idTree(), pages.source()).await().unwrap()
                .forEach(cell -> state.put("o:" + cell.key(), describe(ObjectEntry.fromBytes(cell.value()).unwrap())));
        PageTree.cells(root.nameTree(), pages.source()).await().unwrap()
                .forEach(cell -> state.put("n:" + cell.key(), new ObjectId(cell.value()).asString()));
        state.put("count", Long.toString(root.objectCount()));
        state.put("bytes", Long.toString(root.committedBytes()));

        return state;
    }

    private static String describe(ObjectEntry entry) {
        return entry.name().map(ObjectName::value).or("-") + "/" + entry.meta().size() + "/" + entry.meta().contentType();
    }

    @Test
    void createAndBind_inOneCommit_landInOneRoot() {
        var id = ObjectId.objectId();
        var write = built(CatalogRoot.empty(),
                          CatalogMutation.create(id, entry(10, 1)),
                          CatalogMutation.bind(name("a"), id, Precondition.nameFree()));

        assertThat(write.baseVersion()).isZero();
        assertThat(write.root().version()).isEqualTo(1);
        assertThat(write.root().objectCount()).isEqualTo(1);
        assertThat(write.root().committedBytes()).isEqualTo(10);
        assertThat(holder(write.root(), "a")).isEqualTo(some(id));
        assertThat(entryOf(write.root(), id).flatMap(ObjectEntry::name)).isEqualTo(some(name("a")));
        assertThat(entryOf(write.root(), id).map(ObjectEntry::catalogVersion)).as("entries carry the commit's version").isEqualTo(some(1L));
        assertThat(write.outcomes()).hasSize(2);
        assertThat(CatalogRoot.fromBytes(write.rootBlock()).unwrap()).isEqualTo(write.root());
        assertThat(BlockId.blockId(write.rootBlock()).unwrap()).isEqualTo(write.rootId());
    }

    @Test
    void rename_isOneRoot_theOldNameTheNewNameAndTheEntryChangeTogether() {
        var id = ObjectId.objectId();
        var before = committed(CatalogRoot.empty(),
                               CatalogMutation.create(id, entry(1, 1)),
                               CatalogMutation.bind(name("old"), id, Precondition.nameFree()));
        var after = committed(before, CatalogMutation.bind(name("new"), id, Precondition.nameFree()));

        assertThat(holder(after, "new")).isEqualTo(some(id));
        assertThat(holder(after, "old").isEmpty()).isTrue();
        assertThat(entryOf(after, id).flatMap(ObjectEntry::name)).isEqualTo(some(name("new")));
        assertThat(entryOf(after, id).flatMap(ObjectEntry::alsoBound).isEmpty()).as("no second name is ever recorded").isTrue();
        assertThat(holder(before, "old")).as("the root before is unchanged").isEqualTo(some(id));
        assertThat(holder(before, "new").isEmpty()).isTrue();
    }

    @Test
    void rebind_takesTheNameFromItsPreviousObject_inTheSameRoot() {
        var first = ObjectId.objectId();
        var second = ObjectId.objectId();
        var root = committed(CatalogRoot.empty(),
                             CatalogMutation.create(first, entry(1, 1)),
                             CatalogMutation.create(second, entry(1, 2)),
                             CatalogMutation.bind(name("a"), first, Precondition.nameFree()));
        var after = committed(root, CatalogMutation.bind(name("a"), second, Precondition.boundTo(first)));

        assertThat(holder(after, "a")).isEqualTo(some(second));
        assertThat(entryOf(after, first).flatMap(ObjectEntry::name).isEmpty()).isTrue();
        assertThat(entryOf(after, second).flatMap(ObjectEntry::name)).isEqualTo(some(name("a")));
    }

    @Test
    void aFailingMutation_failsTheWholeCommit() {
        var id = ObjectId.objectId();
        var attempt = CatalogCommit.catalogCommit(List.of(CatalogMutation.create(id, entry(1, 1)),
                                                          CatalogMutation.bind(name("a"), ObjectId.objectId(), Precondition.unconditional())))
                                   .onto(CatalogRoot.empty(), NAMESPACE, pages.source(), FANOUT)
                                   .await();

        assertThat(attempt.isFailure()).isTrue();
        attempt.onFailure(cause -> assertThat(cause).isInstanceOf(ContentStoreError.ObjectNotFound.class));
    }

    @Test
    void createOfAnExistingId_isRefused() {
        var id = ObjectId.objectId();
        var root = committed(CatalogRoot.empty(), CatalogMutation.create(id, entry(1, 1)));
        var again = CatalogCommit.catalogCommit(List.of(CatalogMutation.create(id, entry(2, 2))))
                                 .onto(root, NAMESPACE, pages.source(), FANOUT)
                                 .await();

        again.onSuccess(_ -> { throw new AssertionError("a second create of one id was accepted"); })
             .onFailure(cause -> assertThat(cause).isInstanceOf(ContentStoreError.PreconditionFailed.class));
    }

    @Test
    void aFailedGroup_leavesTheDraftBeforeIt_andTheNextGroupGoesOn() {
        var first = ObjectId.objectId();
        var third = ObjectId.objectId();
        var draft = draft(CatalogRoot.empty()).apply(List.of(CatalogMutation.create(first, entry(1, 1)))).await().unwrap();
        var failed = draft.apply(List.of(CatalogMutation.patch(ObjectId.objectId(), MetaPatch.metaPatch(none(), some("x"), none())))).await();
        var next = draft.apply(List.of(CatalogMutation.create(third, entry(3, 3)))).await().unwrap();
        var write = next.write(FANOUT).await().unwrap();

        pages.store(write.blocks());

        assertThat(failed.isFailure()).isTrue();
        assertThat(write.root().objectCount()).isEqualTo(2);
        assertThat(write.outcomes()).hasSize(2);
        assertThat(entryOf(write.root(), first).isPresent()).isTrue();
        assertThat(entryOf(write.root(), third).isPresent()).isTrue();
    }

    @Test
    void remove_dropsTheEntryAndItsName_andReportsTheObject() {
        var id = ObjectId.objectId();
        var root = committed(CatalogRoot.empty(),
                             CatalogMutation.create(id, entry(7, 1)),
                             CatalogMutation.bind(name("a"), id, Precondition.nameFree()));
        var write = built(root, CatalogMutation.remove(id), CatalogMutation.remove(ObjectId.objectId()));

        assertThat(write.outcomes().getFirst().map(info -> info.ref().id())).isEqualTo(some(id));
        assertThat(write.outcomes().get(1).isEmpty()).as("removing an unknown id reports nothing").isTrue();
        assertThat(write.root().objectCount()).isZero();
        assertThat(write.root().committedBytes()).isZero();
        assertThat(write.root().idTree().isEmpty()).isTrue();
        assertThat(write.root().nameTree().isEmpty()).isTrue();
    }

    @Test
    void patch_keepsTheName_andAddsNoBytes() {
        var id = ObjectId.objectId();
        var root = committed(CatalogRoot.empty(),
                             CatalogMutation.create(id, entry(7, 1)),
                             CatalogMutation.bind(name("a"), id, Precondition.nameFree()));
        var after = committed(root, CatalogMutation.patch(id, MetaPatch.metaPatch(none(), some("text/plain"), none())));

        assertThat(entryOf(after, id).map(found -> found.meta().contentType())).isEqualTo(some("text/plain"));
        assertThat(entryOf(after, id).flatMap(ObjectEntry::name)).isEqualTo(some(name("a")));
        assertThat(after.committedBytes()).isEqualTo(7);
    }

    /// The lost-CAS case: B's bind is prepared on v1 and passes there; A's bind of the same name wins v2; B's commit
    /// rebuilt on v2 must fail its precondition, not overwrite A's binding.
    @Test
    void rebase_rechecksThePrecondition_onTheNewerRoot() {
        var a = ObjectId.objectId();
        var b = ObjectId.objectId();
        var v1 = committed(CatalogRoot.empty(), CatalogMutation.create(a, entry(1, 1)), CatalogMutation.create(b, entry(1, 2)));
        var commitOfB = CatalogCommit.catalogCommit(List.of(CatalogMutation.bind(name("x"), b, Precondition.nameFree())));
        var preparedOnV1 = commitOfB.onto(v1, NAMESPACE, pages.source(), FANOUT).await();
        var v2 = committed(v1, CatalogMutation.bind(name("x"), a, Precondition.nameFree()));
        var rebased = commitOfB.onto(v2, NAMESPACE, pages.source(), FANOUT).await();

        assertThat(preparedOnV1.isSuccess()).as("on v1 the name was free").isTrue();
        assertThat(rebased.isFailure()).as("on v2 the name is bound").isTrue();
        rebased.onFailure(cause -> assertThat(cause).isInstanceOf(ContentStoreError.PreconditionFailed.class));
        assertThat(holder(v2, "x")).isEqualTo(some(a));
    }

    /// A commit rebuilt on a newer root keeps the winner's changes: with group commits of several objects each, the
    /// loser's rebase lands both sets.
    @Test
    void rebase_keepsTheWinnersChanges() {
        var v0 = CatalogRoot.empty();
        var winner = new ArrayList<CatalogMutation>();
        var loser = new ArrayList<CatalogMutation>();

        for (var i = 0; i < 20; i++) {
            winner.add(CatalogMutation.create(ObjectId.objectId(), entry(1, i)));
            loser.add(CatalogMutation.create(ObjectId.objectId(), entry(1, 100 + i)));
        }

        var v1 = committed(v0, winner.toArray(CatalogMutation[]::new));
        var rebased = committed(v1, loser.toArray(CatalogMutation[]::new));

        assertThat(rebased.objectCount()).isEqualTo(40);
        assertThat(PageTree.cells(rebased.idTree(), pages.source()).await().unwrap()).hasSize(40);
        assertThat(rebased.version()).isEqualTo(2);
    }

    /// Two commits on disjoint objects and names give the same catalog whichever wins the CAS and whichever is rebased.
    @Test
    void rebase_commutes_forCommitsOnDisjointKeys() {
        for (var seed = 0L; seed < 30; seed++) {
            var random = new SplittableRandom(seed);
            var base = seeded(random);
            var left = randomCommit(random, base, "l");
            var right = randomCommit(random, base, "r");
            var leftFirst = committed(committed(base.root(), left), right);
            var rightFirst = committed(committed(base.root(), right), left);

            assertThat(state(leftFirst)).as("seed %d", seed).isEqualTo(state(rightFirst));
        }
    }

    private record Seeded(CatalogRoot root, List<ObjectId> left, List<ObjectId> right) {}

    /// A catalog whose objects are split into two disjoint halves, half of them named under each half's own prefix.
    private Seeded seeded(SplittableRandom random) {
        var left = new ArrayList<ObjectId>();
        var right = new ArrayList<ObjectId>();
        var mutations = new ArrayList<CatalogMutation>();

        for (var i = 0; i < 12; i++) {
            var id = ObjectId.objectId();
            var side = i % 2 == 0 ? left : right;
            var prefix = i % 2 == 0 ? "l" : "r";

            side.add(id);
            mutations.add(CatalogMutation.create(id, entry(1 + random.nextInt(50), i)));
            if (random.nextBoolean()) {
                mutations.add(CatalogMutation.bind(name(prefix + i), id, Precondition.nameFree()));
            }
        }

        return new Seeded(committed(CatalogRoot.empty(), mutations.toArray(CatalogMutation[]::new)), left, right);
    }

    /// Random creates, patches, binds, renames and removes, touching only `prefix`'s side of `base`.
    private CatalogMutation[] randomCommit(SplittableRandom random, Seeded base, String prefix) {
        var ids = new ArrayList<>(prefix.equals("l") ? base.left() : base.right());
        var mutations = new ArrayList<CatalogMutation>();

        for (var i = 0; i < 1 + random.nextInt(8); i++) {
            var id = ids.get(random.nextInt(ids.size()));

            switch (random.nextInt(5)) {
                case 0 -> {
                    var created = ObjectId.objectId();

                    ids.add(created);
                    mutations.add(CatalogMutation.create(created, entry(1 + random.nextInt(50), 200 + i)));
                }
                case 1 -> mutations.add(CatalogMutation.patch(id, MetaPatch.metaPatch(none(), some(prefix + "/type" + i), none())));
                case 2 -> mutations.add(CatalogMutation.bind(name(prefix + random.nextInt(12)), id, Precondition.unconditional()));
                case 3 -> mutations.add(CatalogMutation.bind(name(prefix + "new" + i), id, Precondition.unconditional()));
                default -> {
                    ids.remove(id);
                    ids.add(ObjectId.objectId());
                    mutations.add(CatalogMutation.remove(id));
                }
            }
        }

        // patches of removed objects would fail the commit; keep only mutations whose object is still there when applied
        return applicable(base.root(), mutations);
    }

    private CatalogMutation[] applicable(CatalogRoot root, List<CatalogMutation> mutations) {
        var kept = new ArrayList<CatalogMutation>();
        var draft = draft(root);

        for (var mutation : mutations) {
            var next = draft.apply(List.of(mutation)).await();

            if (next.isSuccess()) {
                kept.add(mutation);
                draft = next.unwrap();
            }
        }

        return kept.toArray(CatalogMutation[]::new);
    }

    @Test
    void rootCodec_roundTrips_andRefusesWhatItDoesNotKnow() {
        var root = CatalogRoot.catalogRoot(5, some(new BlockId(new byte[Sha256.LENGTH])), none(), 77, 3);
        var bytes = root.toBytes().unwrap();
        var unknownFormat = bytes.clone();

        unknownFormat[ContentEnvelope.marker().length + 1] = 9;

        assertThat(CatalogRoot.fromBytes(bytes).unwrap()).isEqualTo(root);
        assertThat(CatalogRoot.fromBytes(unknownFormat).isFailure()).isTrue();
        assertThat(CatalogRoot.fromBytes(Arrays.copyOf(bytes, bytes.length + 1)).isFailure()).isTrue();
        assertThat(CatalogRoot.fromBytes(ContentEnvelope.wrap(ContentEnvelope.PAGE, new byte[]{1})).isFailure()).isTrue();
    }
}
