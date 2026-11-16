package com.dsh.android.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** UI 插槽声明的纯校验：slot 枚举与 handler 函数名。 */
public class UiSlotDeclTest {

    @Test
    public void allDocumentedSlotsAreAccepted() {
        assertNull(UiSlotDecl.validateSlot("message.header"));
        assertNull(UiSlotDecl.validateSlot("message.footer"));
        assertNull(UiSlotDecl.validateSlot("composer.above"));
        assertNull(UiSlotDecl.validateSlot("settings.section"));
        assertNull(UiSlotDecl.validateSlot("drawer.footer"));
        assertNull(UiSlotDecl.validateSlot("home.hero"));
        assertEquals(6, UiSlotDecl.SLOTS.size());
    }

    @Test
    public void unknownSlotIsRejected() {
        assertNotNull(UiSlotDecl.validateSlot(""));
        assertNotNull(UiSlotDecl.validateSlot(null));
        assertNotNull(UiSlotDecl.validateSlot("home"));
        assertNotNull(UiSlotDecl.validateSlot("settings.after"));
    }

    @Test
    public void handlerMustBeAFunctionName() {
        assertNull(UiSlotDecl.validateHandler("renderHero"));
        assertNull(UiSlotDecl.validateHandler("_fn$1"));
        assertNotNull(UiSlotDecl.validateHandler(""));
        assertNotNull(UiSlotDecl.validateHandler("1bad"));
        assertNotNull(UiSlotDecl.validateHandler("has-dash"));
        assertNotNull(UiSlotDecl.validateHandler("a b"));
    }
}
