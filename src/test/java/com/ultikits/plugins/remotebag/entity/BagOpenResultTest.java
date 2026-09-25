package com.ultikits.plugins.remotebag.entity;

import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.enums.LockType;

import org.junit.jupiter.api.*;

import com.ultikits.plugins.remotebag.i18n.CatalogueText;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BagOpenResult Tests")
class BagOpenResultTest {

    /**
     * The notice a result shows under {@code language: zh}, as a player sees it: the result renders
     * its text from the module's real zh catalogue (UltiKits/UltiRemoteBag#20). Reached by reflection
     * so this file compiles against a tree that does not have the rendering method yet, which a revert
     * proof needs.
     */
    static String render(BagOpenResult result) {
        return render(result, "zh");
    }

    /** The notice a result shows under {@code language: code}. */
    static String render(BagOpenResult result, String code) {
        UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
        when(plugin.i18n(anyString())).thenAnswer(CatalogueText.answer(code));
        try {
            Method m = BagOpenResult.class.getMethod("renderMessage", UltiToolsPlugin.class);
            return (String) m.invoke(result, plugin);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("BagOpenResult cannot render its notice through the language file", e);
        }
    }

    /**
     * The private four-argument constructor, found by arity: its third parameter is the notice, whose
     * type changed when the text moved to the language file (UltiKits/UltiRemoteBag#20).
     */
    @SuppressWarnings("unchecked")
    static Constructor<BagOpenResult> fourArgConstructor() throws NoSuchMethodException {
        for (Constructor<?> c : BagOpenResult.class.getDeclaredConstructors()) {
            if (c.getParameterCount() == 4) {
                return (Constructor<BagOpenResult>) c;
            }
        }
        throw new NoSuchMethodException("BagOpenResult(boolean, AccessMode, <notice>, BagLockInfo)");
    }

    /** A non-null value of the constructor's notice parameter, whatever its type. */
    static Object aNotice(Constructor<BagOpenResult> ctor) {
        Class<?> type = ctor.getParameterTypes()[2];
        return type.isEnum() ? type.getEnumConstants()[0] : "message";
    }

    @Nested
    @DisplayName("Notices follow the language setting (UltiKits/UltiRemoteBag#20)")
    class Language {

        private BagLockInfo lock(String holder, LockType type) {
            return BagLockInfo.builder().holderUuid(UUID.randomUUID()).holderName(holder)
                    .lockType(type).acquiredAt(System.currentTimeMillis()).build();
        }

        @Test
        @DisplayName("under language: en each notice is the English catalogue text with the holder's name")
        void englishNotices() {
            String readOnly = CatalogueText.text("en", "bag_read_only_in_use").replace("{PLAYER}", "Owner");
            String byAdmin = CatalogueText.text("en", "bag_blocked_by_admin").replace("{PLAYER}", "Admin");
            String inUse = CatalogueText.text("en", "bag_blocked_in_use").replace("{PLAYER}", "Other");

            assertThat(render(BagOpenResult.readOnlyMode(lock("Owner", LockType.OWNER)), "en")).isEqualTo(readOnly);
            assertThat(render(BagOpenResult.blocked(lock("Admin", LockType.ADMIN)), "en")).isEqualTo(byAdmin);
            assertThat(render(BagOpenResult.blocked(lock("Other", LockType.OWNER)), "en")).isEqualTo(inUse);
            assertThat(readOnly).doesNotContainPattern("[\\x{4e00}-\\x{9fff}]");
        }
    }

    @Nested
    @DisplayName("Edit Mode Factory")
    class EditModeFactory {

        @Test
        @DisplayName("Should create edit mode result")
        void createsEditMode() {
            BagOpenResult result = BagOpenResult.editMode();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAccessMode()).isEqualTo(AccessMode.EDIT);
            assertThat(render(result)).isNull();
            assertThat(result.getExistingLock()).isNull();
        }

        @Test
        @DisplayName("isEditMode should return true")
        void isEditModeTrue() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result.isEditMode()).isTrue();
        }

        @Test
        @DisplayName("isReadOnlyMode should return false")
        void isReadOnlyModeFalse() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result.isReadOnlyMode()).isFalse();
        }

        @Test
        @DisplayName("Edit mode result should have null existing lock")
        void editModeHasNullLock() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result.getExistingLock()).isNull();
        }

        @Test
        @DisplayName("Edit mode result should have null message")
        void editModeHasNullMessage() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(render(result)).isNull();
        }
    }

    @Nested
    @DisplayName("Read Only Mode Factory")
    class ReadOnlyModeFactory {

        @Test
        @DisplayName("Should create read only mode result")
        void createsReadOnlyMode() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Owner")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAccessMode()).isEqualTo(AccessMode.READ_ONLY);
            assertThat(render(result)).contains("Owner");
            assertThat(render(result)).contains("只读模式");
            assertThat(result.getExistingLock()).isSameAs(lock);
        }

        @Test
        @DisplayName("isEditMode should return false")
        void isEditModeFalse() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Owner")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);
            assertThat(result.isEditMode()).isFalse();
        }

        @Test
        @DisplayName("isReadOnlyMode should return true")
        void isReadOnlyModeTrue() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Owner")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);
            assertThat(result.isReadOnlyMode()).isTrue();
        }

        @Test
        @DisplayName("Message should contain holder name")
        void messageContainsHolderName() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("SpecificPlayerName")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);
            assertThat(render(result)).contains("SpecificPlayerName");
        }

        @Test
        @DisplayName("Message should include color code marker")
        void messageContainsColorCode() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Owner")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);
            // Message starts with color code section symbol
            assertThat(render(result)).startsWith("\u00a7");
        }

        @Test
        @DisplayName("Should preserve lock reference")
        void preservesLockReference() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Owner")
                    .lockType(LockType.OWNER)
                    .acquiredAt(12345L)
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);
            assertThat(result.getExistingLock().getAcquiredAt()).isEqualTo(12345L);
        }
    }

    @Nested
    @DisplayName("Blocked Factory")
    class BlockedFactory {

        @Test
        @DisplayName("Should create blocked result for owner lock")
        void blockedOwnerLock() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("OtherPlayer")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getAccessMode()).isNull();
            assertThat(render(result)).contains("OtherPlayer");
            assertThat(render(result)).contains("使用中");
            assertThat(result.getExistingLock()).isSameAs(lock);
        }

        @Test
        @DisplayName("Should create blocked result for admin lock")
        void blockedAdminLock() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("AdminPlayer")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getAccessMode()).isNull();
            assertThat(render(result)).contains("管理员");
            assertThat(render(result)).contains("AdminPlayer");
            assertThat(result.getExistingLock()).isSameAs(lock);
        }

        @Test
        @DisplayName("Admin blocked message should not contain generic usage text")
        void adminBlockedMessageDiffers() {
            BagLockInfo adminLock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Admin1")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagLockInfo ownerLock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Owner1")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult adminResult = BagOpenResult.blocked(adminLock);
            BagOpenResult ownerResult = BagOpenResult.blocked(ownerLock);

            // Admin message should contain "管理员", owner message should not
            assertThat(render(adminResult)).contains("管理员");
            assertThat(render(ownerResult)).doesNotContain("管理员");
        }

        @Test
        @DisplayName("Blocked message should start with red color code")
        void blockedMessageStartsWithRed() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);
            assertThat(render(result)).startsWith("\u00a7c");
        }

        @Test
        @DisplayName("isEditMode should return false")
        void isEditModeFalse() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);
            assertThat(result.isEditMode()).isFalse();
        }

        @Test
        @DisplayName("isReadOnlyMode should return false")
        void isReadOnlyModeFalse() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);
            assertThat(result.isReadOnlyMode()).isFalse();
        }

        @Test
        @DisplayName("Blocked result should have null access mode")
        void blockedHasNullAccessMode() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);
            assertThat(result.getAccessMode()).isNull();
        }
    }

    @Nested
    @DisplayName("Getters")
    class Getters {

        @Test
        @DisplayName("Should return all fields for read only mode")
        void gettersWorkReadOnly() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Test")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAccessMode()).isEqualTo(AccessMode.READ_ONLY);
            assertThat(render(result)).isNotNull();
            assertThat(result.getExistingLock()).isNotNull();
        }

        @Test
        @DisplayName("Should return all fields for edit mode")
        void gettersWorkEditMode() {
            BagOpenResult result = BagOpenResult.editMode();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAccessMode()).isEqualTo(AccessMode.EDIT);
            assertThat(render(result)).isNull();
            assertThat(result.getExistingLock()).isNull();
        }

        @Test
        @DisplayName("Should return all fields for blocked mode")
        void gettersWorkBlocked() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Blocker")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getAccessMode()).isNull();
            assertThat(render(result)).isNotNull();
            assertThat(result.getExistingLock()).isSameAs(lock);
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("Two edit mode results should be equal")
        void editModesEqual() {
            BagOpenResult result1 = BagOpenResult.editMode();
            BagOpenResult result2 = BagOpenResult.editMode();

            assertThat(result1).isEqualTo(result2);
            assertThat(result1.hashCode()).isEqualTo(result2.hashCode());
        }

        @Test
        @DisplayName("Edit mode and blocked should not be equal")
        void editModeNotEqualToBlocked() {
            BagOpenResult edit = BagOpenResult.editMode();
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();
            BagOpenResult blocked = BagOpenResult.blocked(lock);

            assertThat(edit).isNotEqualTo(blocked);
        }

        @Test
        @DisplayName("Result should not be equal to null")
        void notEqualToNull() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Result should not be equal to different type")
        void notEqualToDifferentType() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result).isNotEqualTo("not a result");
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTests {

        @Test
        @DisplayName("Edit mode toString should contain relevant fields")
        void editModeToString() {
            BagOpenResult result = BagOpenResult.editMode();
            String str = result.toString();

            assertThat(str).contains("success=true");
            assertThat(str).contains("EDIT");
        }

        @Test
        @DisplayName("Blocked mode toString should contain relevant fields")
        void blockedToString() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);
            String str = result.toString();

            assertThat(str).contains("success=false");
        }

        @Test
        @DisplayName("Read only mode toString should contain relevant fields")
        void readOnlyToString() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("ReadOnlyPlayer")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);
            String str = result.toString();

            assertThat(str).contains("success=true");
            assertThat(str).contains("READ_ONLY");
        }
    }

    @Nested
    @DisplayName("Equals Edge Cases")
    class EqualsEdgeCases {

        @Test
        @DisplayName("Should equal itself")
        void equalsItself() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result).isEqualTo(result);
        }

        @Test
        @DisplayName("canEqual should return true for same type")
        void canEqualSameType() {
            BagOpenResult a = BagOpenResult.editMode();
            BagOpenResult b = BagOpenResult.editMode();
            assertThat(a.canEqual(b)).isTrue();
        }

        @Test
        @DisplayName("canEqual should return false for different type")
        void canEqualDifferentType() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result.canEqual("string")).isFalse();
        }

        @Test
        @DisplayName("Two read-only with same lock should be equal")
        void readOnlyEqual() {
            UUID uuid = UUID.randomUUID();
            BagLockInfo lock1 = BagLockInfo.builder()
                    .holderUuid(uuid)
                    .holderName("Same")
                    .lockType(LockType.OWNER)
                    .acquiredAt(1000L)
                    .build();
            BagLockInfo lock2 = BagLockInfo.builder()
                    .holderUuid(uuid)
                    .holderName("Same")
                    .lockType(LockType.OWNER)
                    .acquiredAt(1000L)
                    .build();

            BagOpenResult a = BagOpenResult.readOnlyMode(lock1);
            BagOpenResult b = BagOpenResult.readOnlyMode(lock2);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Read-only and edit should not be equal")
        void readOnlyNotEqualToEdit() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(1000L)
                    .build();

            BagOpenResult readOnly = BagOpenResult.readOnlyMode(lock);
            BagOpenResult edit = BagOpenResult.editMode();

            assertThat(readOnly).isNotEqualTo(edit);
        }

        @Test
        @DisplayName("Blocked results with different locks should not be equal")
        void blockedDifferentLocks() {
            BagLockInfo lock1 = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player1")
                    .lockType(LockType.OWNER)
                    .acquiredAt(1000L)
                    .build();
            BagLockInfo lock2 = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player2")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(2000L)
                    .build();

            BagOpenResult a = BagOpenResult.blocked(lock1);
            BagOpenResult b = BagOpenResult.blocked(lock2);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should handle null accessMode equality via reflection")
        void handlesNullAccessMode() throws Exception {
            // blocked() creates results with null accessMode
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(1000L)
                    .build();

            BagOpenResult a = BagOpenResult.blocked(lock);
            BagOpenResult b = BagOpenResult.blocked(lock);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Should handle null message vs non-null message")
        void handlesNullVsNonNullMessage() {
            // editMode() has null message, readOnlyMode() has non-null message
            BagOpenResult edit = BagOpenResult.editMode();
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(1000L)
                    .build();
            BagOpenResult readOnly = BagOpenResult.readOnlyMode(lock);

            assertThat(edit).isNotEqualTo(readOnly);
        }

        @Test
        @DisplayName("Should handle null existingLock vs non-null existingLock")
        void handlesNullVsNonNullLock() {
            // editMode() has null lock, blocked() has non-null lock
            BagOpenResult edit = BagOpenResult.editMode();
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(1000L)
                    .build();
            BagOpenResult blocked = BagOpenResult.blocked(lock);

            assertThat(edit).isNotEqualTo(blocked);
        }

        @Test
        @DisplayName("Should handle null accessMode in both objects")
        void handlesNullAccessModeInBoth() throws Exception {
            // Use reflection to create two results with all null nullable fields
            Constructor<BagOpenResult> ctor = fourArgConstructor();
            ctor.setAccessible(true);

            BagOpenResult a = ctor.newInstance(false, null, null, null);
            BagOpenResult b = ctor.newInstance(false, null, null, null);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Should not equal when one has null accessMode and other does not")
        void notEqualNullVsNonNullAccessMode() throws Exception {
            Constructor<BagOpenResult> ctor = fourArgConstructor();
            ctor.setAccessible(true);

            BagOpenResult a = ctor.newInstance(true, null, null, null);
            BagOpenResult b = ctor.newInstance(true, AccessMode.EDIT, null, null);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not equal when one has null message and other does not")
        void notEqualNullVsNonNullMessageReflection() throws Exception {
            Constructor<BagOpenResult> ctor = fourArgConstructor();
            ctor.setAccessible(true);

            BagOpenResult a = ctor.newInstance(true, AccessMode.EDIT, null, null);
            BagOpenResult b = ctor.newInstance(true, AccessMode.EDIT, aNotice(ctor), null);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not equal when one has null lock and other does not")
        void notEqualNullVsNonNullLockReflection() throws Exception {
            Constructor<BagOpenResult> ctor = fourArgConstructor();
            ctor.setAccessible(true);

            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(1000L)
                    .build();

            BagOpenResult a = ctor.newInstance(true, AccessMode.EDIT, null, null);
            BagOpenResult b = ctor.newInstance(true, AccessMode.EDIT, null, lock);

            assertThat(a).isNotEqualTo(b);
        }
    }
}
