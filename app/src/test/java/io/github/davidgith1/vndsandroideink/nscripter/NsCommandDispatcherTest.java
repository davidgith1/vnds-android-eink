package io.github.davidgith1.vndsandroideink.nscripter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class NsCommandDispatcherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final File vnDir = new File("/fake/vn");
    private FakeListener listener = new FakeListener();

    private void exec(NsExecState state, String rawLine) {
        NsCommandDispatcher.execute(NsTokenizer.classify(rawLine), state, listener, vnDir);
    }

    @Test
    public void movAssignsNumericVariable() {
        NsExecState state = new NsExecState();
        exec(state, "mov %1,42");
        assertEquals(Long.valueOf(42), state.numVars.get(1));
    }

    @Test
    public void movAssignsStringVariable() {
        NsExecState state = new NsExecState();
        exec(state, "mov $1,\"hello\"");
        assertEquals("hello", state.strVars.get(1));
    }

    @Test
    public void movEvaluatesArithmeticExpression() {
        NsExecState state = new NsExecState();
        state.numVars.put(2, 10L);
        exec(state, "mov %1,%2+5");
        assertEquals(Long.valueOf(15), state.numVars.get(1));
    }

    @Test
    public void addAndSubMutateNumericVariable() {
        NsExecState state = new NsExecState();
        state.numVars.put(1, 10L);
        exec(state, "add %1,5");
        assertEquals(Long.valueOf(15), state.numVars.get(1));
        exec(state, "sub %1,3");
        assertEquals(Long.valueOf(12), state.numVars.get(1));
    }

    @Test
    public void addConcatenatesStrings() {
        NsExecState state = new NsExecState();
        state.strVars.put(1, "foo");
        exec(state, "add $1,\"bar\"");
        assertEquals("foobar", state.strVars.get(1));
    }

    @Test
    public void incAndDec() {
        NsExecState state = new NsExecState();
        state.numVars.put(1, 5L);
        exec(state, "inc %1");
        assertEquals(Long.valueOf(6), state.numVars.get(1));
        exec(state, "dec %1");
        exec(state, "dec %1");
        assertEquals(Long.valueOf(4), state.numVars.get(1));
    }

    @Test
    public void numaliasLetsScriptsNameAVariableSlot() {
        NsExecState state = new NsExecState();
        exec(state, "numalias money,3");
        exec(state, "mov %money,100");
        assertEquals(Long.valueOf(100), state.numVars.get(3));
    }

    @Test
    public void straliasLetsScriptsNameAStringVariableSlot() {
        NsExecState state = new NsExecState();
        exec(state, "stralias name,7");
        exec(state, "mov $name,\"Yuki\"");
        assertEquals("Yuki", state.strVars.get(7));
    }

    @Test
    public void numaliasAlsoRegistersTheSameNameAsAStringVariableSlot() {
        // Real NScripter has one unified variable-slot space per index (slot N is addressable both
        // as "%N" and "$N"). Scripts commonly declare
        // names via "numalias" alone, including several only ever read/
        // written as a string ("$LdParam2", "$SoundFileName", ...), without ever calling "stralias" at
        // all. Without mirroring, every such "$name" reference fell back to strAliases's own
        // "unknown alias: default to slot 0" tolerance, colliding all of them onto the same slot.
        NsExecState state = new NsExecState();
        exec(state, "numalias SoundFileName,5");
        exec(state, "mov $SoundFileName,\"theme.ogg\"");
        assertEquals("theme.ogg", state.strVars.get(5));
    }

    @Test
    public void straliasWithANumericSlotAlsoRegistersTheSameNameAsANumericVariableSlot() {
        // The mirror image of numaliasAlsoRegistersTheSameNameAsAStringVariableSlot -- same unified
        // slot space, whichever alias command a script happens to declare first.
        NsExecState state = new NsExecState();
        exec(state, "stralias money,9");
        exec(state, "mov %money,100");
        assertEquals(Long.valueOf(100), state.numVars.get(9));
    }

    @Test
    public void straliasWithAStringLiteralDefinesABarewordConstantInstead() {
        // ONScripter-EN's dual-purpose "stralias": a
        // literal-string 2nd argument defines a bareword text constant ("bg bgcoffee,10" later
        // resolves "bgcoffee" to this value), not a variable-slot alias.
        NsExecState state = new NsExecState();
        exec(state, "stralias bgcoffee,\"data\\bg_coffee.png\"");
        exec(state, "bg bgcoffee,10");
        assertEquals(new File(vnDir, "data/bg_coffee.png"), listener.lastBackground);
        assertEquals(0, state.strAliases.size()); // not registered as a variable-slot alias
    }

    @Test
    public void ldResolvesABarewordFileConstantIncludingItsTag() {
        // e.g. "stralias sophieblush,\":a;data\\sophie_blush.png\"" then "ld c,sophieblush,10" --
        // the resolved value's own ":a;" tag must still drive transparency detection.
        NsExecState state = new NsExecState();
        exec(state, "stralias sophieblush,\":a;data\\sophie_blush.png\"");
        exec(state, "ld c,sophieblush,10");
        assertEquals(new File(vnDir, "data/sophie_blush.png"), listener.spriteFiles.get(0));
        assertEquals(VnEngine.SpriteTransparency.ALPHA_MASK, listener.spriteTransparencies.get(0));
    }

    @Test
    public void ifRunsConsequentWhenTrue() {
        NsExecState state = new NsExecState();
        state.numVars.put(1, 1L);
        exec(state, "if %1==1 mov %2,99");
        assertEquals(Long.valueOf(99), state.numVars.get(2));
    }

    @Test
    public void ifSkipsConsequentWhenFalse() {
        NsExecState state = new NsExecState();
        state.numVars.put(1, 0L);
        exec(state, "if %1==1 mov %2,99");
        assertNull(state.numVars.get(2));
    }

    @Test
    public void notifInvertsCondition() {
        NsExecState state = new NsExecState();
        state.numVars.put(1, 0L);
        exec(state, "notif %1==1 mov %2,99");
        assertEquals(Long.valueOf(99), state.numVars.get(2));
    }

    @Test
    public void ifComparesStringsWhenLeftOperandIsStringVar() {
        NsExecState state = new NsExecState();
        state.strVars.put(1, "yes");
        exec(state, "if $1==\"yes\" mov %2,1");
        assertEquals(Long.valueOf(1), state.numVars.get(2));
    }

    @Test
    public void ifChainsTwoConditionsWithAmpersandAsLogicalAnd() {
        // Real NScripter syntax for a compound range check, e.g. "if %BtnRes >= 500 &
        // %BtnRes <= 599 ...", a pattern real scripts use commonly. Before this was supported, the
        // "& ..." remainder was misparsed as the consequent
        // itself and (since it starts with '&', not a recognized command) shown as literal garbage
        // dialogue instead of being evaluated -- and since the first half alone was already enough
        // to make the (wrongly parsed) "if" true, the intended consequent never ran at all.
        NsExecState state = new NsExecState();
        state.numVars.put(1, 5L);
        exec(state, "if %1 >= 1 & %1 <= 10 mov %2,99");
        assertEquals(Long.valueOf(99), state.numVars.get(2));

        state = new NsExecState();
        state.numVars.put(1, 50L); // fails the 2nd half
        exec(state, "if %1 >= 1 & %1 <= 10 mov %2,99");
        assertNull(state.numVars.get(2));
    }

    @Test
    public void ifChainsThreeConditionsWithAmpersand() {
        // e.g. real scripts commonly use "if %11=0 & %12=0 & %13=0 return".
        NsExecState state = new NsExecState();
        state.numVars.put(11, 0L);
        state.numVars.put(12, 0L);
        state.numVars.put(13, 0L);
        exec(state, "if %11=0 & %12=0 & %13=0 mov %2,99");
        assertEquals(Long.valueOf(99), state.numVars.get(2));

        state.numVars.put(13, 1L); // breaks the chain
        exec(state, "if %11=0 & %12=0 & %13=0 mov %2,100");
        assertEquals(Long.valueOf(99), state.numVars.get(2)); // unchanged
    }

    @Test
    public void ifChainsConditionsWithPipeAsLogicalOr() {
        NsExecState state = new NsExecState();
        state.numVars.put(1, 0L);
        exec(state, "if %1 == 1 | %1 == 0 mov %2,99");
        assertEquals(Long.valueOf(99), state.numVars.get(2));
    }

    @Test
    public void ifChainedConditionFallsBackToDialogueWhenTheChainLooksBrokenAfterAmpersand() {
        // If what follows "&"/"|" doesn't actually parse as another comparison, the tolerant
        // fallback treats it as the real (non-chained) consequent, same as any other unparseable
        // trailing text elsewhere in this dispatcher -- not a silent no-op.
        NsExecState state = new NsExecState();
        state.numVars.put(1, 1L);
        exec(state, "if %1==1 & not a condition");
        assertEquals(java.util.Collections.singletonList("& not a condition"), listener.textLines);
    }

    @Test
    public void gotoJumpsToLabel() {
        NsExecState state = new NsExecState();
        state.labelIndex.put("later", 5);
        state.pc = 0;
        exec(state, "goto *later");
        assertEquals(5, state.pc);
    }

    @Test
    public void gosubPushesReturnAddressAndReturnPopsIt() {
        NsExecState state = new NsExecState();
        state.labelIndex.put("sub", 10);
        state.pc = 3;
        exec(state, "gosub *sub");
        assertEquals(10, state.pc);
        assertEquals(1, state.callStack.size());
        state.pc = 20;
        exec(state, "return");
        assertEquals(3, state.pc);
        assertTrue(state.callStack.isEmpty());
    }

    @Test
    public void returnWithEmptyStackIsANoOp() {
        NsExecState state = new NsExecState();
        state.pc = 7;
        exec(state, "return");
        assertEquals(7, state.pc);
    }

    @Test
    public void clClearsOneLayerOrAllWithSentinel() {
        NsExecState state = new NsExecState();
        exec(state, "cl 2");
        assertEquals(Integer.valueOf(2), listener.clearedLayers.get(0));
        exec(state, "cl a");
        assertEquals(Integer.valueOf(-1), listener.clearedLayers.get(1));
    }

    @Test
    public void cspClearsTheLayerNumberStoredInAVariableNotTheVariablesOwnIndex() {
        // "csp %0" must clear whatever layer number %0 currently holds, NOT literally layer 0 --
        // a common real-world pattern is a "for %0=701 to 709 ... csp %0 ... next" cleanup
        // loop. Before this was fixed, clearLayerHandler read args.get(0).value directly (the raw
        // variable NAME/index text, e.g. "0"), never evaluating it via NsExpr.numeric -- so a loop
        // meant to clear layers 701..709 always cleared layer 0 instead, nine times.
        NsExecState state = new NsExecState();
        state.numVars.put(0, 705L);
        exec(state, "csp %0");
        assertEquals(Integer.valueOf(705), listener.clearedLayers.get(0));
    }

    @Test
    public void bgResolvesFileUnderVnDir() {
        NsExecState state = new NsExecState();
        exec(state, "bg room.png");
        assertEquals(new File(vnDir, "room.png"), listener.lastBackground);
        assertFalse(listener.lastBackgroundCleared);
    }

    @Test
    public void nsadirRedirectsAssetResolutionToASubdirectory() {
        // "nsadir" points asset/archive resolution at a subdirectory of the VN's own root instead
        // of the root itself. A script commonly declares "nsadir \"data\"" in its header because
        // its actual "arc.nsa"
        // and loose asset files sit under a "data/" folder, not the VN root. Before this was
        // recognized, every asset load still looked directly under the VN root, so nothing in that
        // subdirectory (every image and sound in that game) ever resolved.
        NsExecState state = new NsExecState();
        exec(state, "nsadir \"data\"");
        exec(state, "bg room.png");
        assertEquals(new File(new File(vnDir, "data"), "room.png"), listener.lastBackground);
    }

    @Test
    public void commandNameIsRecognizedEvenWithNoSeparatorBeforeAQuotedArgument() {
        // Real ONScripter-EN terminates a command mnemonic at the first character outside
        // [a-zA-Z0-9_] -- NOT
        // specifically whitespace or a comma. Real scripts routinely omit any separator at all
        // before a quoted first argument, e.g.
        // "bg\"e\\zigzag.jpg\",12" / "caption\"My Game\"" / "bgm\"m\\theme.mid\"". Before
        // NsLine.firstToken() was fixed to match, the whole quoted string was swallowed into the
        // "command name" itself, producing a bareword that matched nothing -- so every background
        // in that game loaded via this idiom silently failed (shown as a black screen).
        NsExecState state = new NsExecState();
        exec(state, "bg\"room.png\",12");
        assertEquals(new File(vnDir, "room.png"), listener.lastBackground);

        exec(state, "bgm\"theme.mid\"");
        assertEquals(new File(vnDir, "theme.mid"), listener.lastMusic);
    }

    @Test
    public void bgHandlerIsAlwaysOpaqueRegardlessOfAnyTag() {
        // Unlike "ld"/"lsp", a background's transparency is NOT tag-driven -- real
        // ONScripter unconditionally sets "bg_info.trans_mode
        // = AnimationInfo::TRANS_COPY" (plain opaque copy) every time a new background is set,
        // regardless of any ":a;"/":l;" tag on the filename argument -- honoring such a tag here
        // (as an earlier version of this handler did) incorrectly color-keyed/alpha-masked real
        // background art, eating a chunk of it into a transparent hole wherever the image's own
        // corner-pixel color recurred elsewhere in the scene.
        NsExecState state = new NsExecState();
        exec(state, "bg \":a;title_text.jpg\"");
        assertEquals(new File(vnDir, "title_text.jpg"), listener.lastBackground);
        assertEquals(VnEngine.SpriteTransparency.OPAQUE, listener.lastBackgroundTransparency);

        exec(state, "bg room.png"); // untagged: also opaque, not "ld"'s own TOPLEFT_KEY default
        assertEquals(VnEngine.SpriteTransparency.OPAQUE, listener.lastBackgroundTransparency);

        exec(state, "bg \":c;room.png\"");
        assertEquals(VnEngine.SpriteTransparency.OPAQUE, listener.lastBackgroundTransparency);
    }

    @Test
    public void bgTildeClearsBackground() {
        NsExecState state = new NsExecState();
        exec(state, "bg ~");
        assertTrue(listener.lastBackgroundCleared);
    }

    @Test
    public void bgClearsTheLeftCenterRightPortraitLayers() {
        // Real ONScripter always clears its internal left/center/right stand-position slots
        // before touching the background itself -- this dispatcher's TACHI_LAYER_LEFT/CENTER/RIGHT
        // sentinels (see layerIndexFor's own doc for why these are deliberately NOT plain 0/1/2:
        // real ONScripter keeps those stand-position slots completely separate from "lsp"'s numbered
        // sprites, which a script might legitimately number 0/1/2 itself), regardless of what the new
        // background turns out to be.
        NsExecState state = new NsExecState();
        exec(state, "bg room.png");
        assertEquals(Arrays.asList(
                        NsCommandDispatcher.TACHI_LAYER_LEFT,
                        NsCommandDispatcher.TACHI_LAYER_CENTER,
                        NsCommandDispatcher.TACHI_LAYER_RIGHT),
                listener.clearedLayers);
    }

    @Test
    public void bgDoesNotClearANumberedLspSpriteEvenIfItSharesANumberWithATachiSlot() {
        // The actual bug this fix addresses: "lsp 1,...(body)" /
        // "lsp 0,...(head)" sprites were being silently wiped out by a later "bg" call, because an
        // earlier, less correct version of this dispatcher cleared plain layers 0/1/2 for "bg" --
        // colliding with legitimate numbered "lsp" layers a script is free to choose, since real
        // ONScripter's tachi_info and sprite_info are genuinely separate arrays that never collide.
        NsExecState state = new NsExecState();
        exec(state, "lsp 1,\":a;body.png\",0,2");
        exec(state, "lsp 0,\":a;head.png\",32,0");
        exec(state, "bg room.png");
        assertFalse("lsp layer 1 (body) must survive a 'bg' call", listener.clearedLayers.contains(1));
        assertFalse("lsp layer 0 (head) must survive a 'bg' call", listener.clearedLayers.contains(0));
    }

    @Test
    public void lspLoadsAPlainImageSpriteAtItsLiteralPosition() {
        // Scripts sometimes load a background this way instead of
        // via "bg": "lsp 50,\":c;dat\\bg\\bg04_1.jpg\",-240,0".
        NsExecState state = new NsExecState();
        exec(state, "lsp 50,\":c;bg04_1.jpg\",-240,0");
        assertEquals(50, listener.spriteLayersXY.get(0)[0]);
        assertEquals(-240, listener.spriteLayersXY.get(0)[1]);
        assertEquals(0, listener.spriteLayersXY.get(0)[2]);
        assertEquals(new File(vnDir, "bg04_1.jpg"), listener.spriteFiles.get(0));
        assertEquals(VnEngine.SpriteTransparency.OPAQUE, listener.spriteTransparencies.get(0));
    }

    @Test
    public void lspStillTracksATextButtonLabelInsteadOfLoadingItAsAnImage() {
        NsExecState state = new NsExecState();
        exec(state, "lsp 1,\":s/36,38,0;#FFFFFF`Start game\",565,430");
        assertTrue(listener.spriteFiles.isEmpty()); // not rendered as an image sprite
    }

    @Test
    public void lspRecognizesATextSpriteWithNoSizePitchBlockAtAll() {
        // Real ONScripter-EN's own parseTaggedString only reads a "/size,size,pitch;" block when a
        // '/' immediately follows "s" -- it's genuinely OPTIONAL, falling back to the sentence
        // font's own default size and jumping straight into the "#RRGGBB" color run with no
        // separating ';' at all otherwise. A real, observed case (night_of_the_forget_me_nots's own
        // script): "lsp 1,\":s#FFFFFF`Come here...\",...", no "/...;" block whatsoever. Before this
        // was handled, requiring a ';' unconditionally misdetected this as a real image file --
        // attempting to resolve the ENTIRE raw ":s#FFFFFF`Come here..." string as a literal
        // filename -- instead of ever tracking "Come here..." as the button's real text label.
        NsExecState state = new NsExecState();
        exec(state, "lsp 1,\":s#FFFFFF`Come here...\",565,430");
        assertTrue("must not be treated as a real image file", listener.spriteFiles.isEmpty());
        exec(state, "spbtn 1,1");
        exec(state, "btnwait %0");
        assertEquals(java.util.Collections.singletonList("Come here..."), listener.lastChoices);
    }

    @Test
    public void spbtnOnALayerNeverLspdRegistersNoButtonAtAll() {
        // Real ONScripter-EN's own spbtnCommand explicitly refuses to register a button for a
        // sprite layer with zero cells -- i.e. one whose "lsp"/"ld" was never actually called (see
        // ONScripterLabel_command.cpp: "if (sprite_info[sprite_no].num_of_cells == 0) return;") --
        // there's nothing visible there for a real player to click, so the button silently doesn't
        // exist. A common real pattern this matters for: an "omake"/bonus-content button
        // conditionally lsp'd only once unlocked ("if %101 > 0 lsp 47,...") but spbtn'd on that same
        // layer UNCONDITIONALLY right after -- before this was fixed, a fresh (not-yet-unlocked)
        // playthrough surfaced a phantom "Button 47" placeholder choice with nothing behind it,
        // letting the player "select" content that was never actually shown.
        NsExecState state = new NsExecState();
        exec(state, "spbtn 103,103");
        exec(state, "btnwait %1");
        assertTrue(listener.lastChoices == null || listener.lastChoices.isEmpty());
        // "btnwait" with nothing registered still blocks for a plain tap (see that handler's own
        // doc), it doesn't silently fall through.
        assertEquals(VnEngine.State.WAITING_TAP, state.runState);
    }

    @Test
    public void spbtnPrefersTheImageSpriteFilenameOverABareButtonId() {
        // A title menu commonly does "lsp 49,\":a/2,0,3;
        // dat\\menu\\hajime.jpg\",410,80" then "spbtn 49,49" -- "hajime" ("start", in Japanese)
        // is more recognizable than a bare "Button 49", even though it's not translated.
        NsExecState state = new NsExecState();
        exec(state, "lsp 49,\":a/2,0,3;dat\\menu\\hajime.jpg\",410,80");
        exec(state, "spbtn 49,49");
        exec(state, "btnwait %1");
        assertEquals(java.util.Collections.singletonList("hajime"), listener.lastChoices);
    }

    @Test
    public void spbtnOffersTheLspImageAlongsideTheTextChoiceMenu() {
        // A real host wants to render the actual button graphic (see ReaderActivity's split
        // image/text choice layout), not just the filename-derived text fallback -- "spbtn" should
        // carry the same resolved image file "lsp" loaded at that layer through to "btnwait"'s
        // onChoices(options, images) call.
        NsExecState state = new NsExecState();
        exec(state, "lsp 49,\":a/2,0,3;dat\\menu\\hajime.jpg\",410,80");
        exec(state, "spbtn 49,49");
        exec(state, "btnwait %1");
        assertEquals(java.util.Collections.singletonList(new File(vnDir, "dat/menu/hajime.jpg")),
                listener.lastChoiceImages);
    }

    @Test
    public void spbtnOffersNoImageForATextLabeledButton() {
        // A "lsp"-":s/…;…" text-sprite button has no real image of its own, unlike a plain
        // image-sprite one (see spbtnOffersTheLspImageAlongsideTheTextChoiceMenu above).
        NsExecState state = new NsExecState();
        exec(state, "lsp 1,\":s/36,38,0;#FFFFFF`Start game\",565,430");
        exec(state, "spbtn 1,1");
        exec(state, "btnwait %1");
        assertEquals(java.util.Collections.singletonList("Start game"), listener.lastChoices);
        assertEquals(java.util.Collections.singletonList(null), listener.lastChoiceImages);
    }

    @Test
    public void lsphLoadsTheImageWithoutShowingItYet() {
        // Real ONScripter-EN dispatches "lsp" and "lsph" from the exact same lspCommand, differing
        // only in initial visibility -- "lsph" loads the image into the sprite slot right away (so
        // a following "spbtn" on that layer can register a real button for it) but does NOT display
        // it, unlike "lsp". A real pattern this backs: a "close menu" icon "lsph"-loaded once at
        // menu setup, only actually revealed later via "vsp" once some prerequisite state is set.
        NsExecState state = new NsExecState();
        exec(state, "lsph 6,\"dat\\system\\close.jpg\",100,200");
        assertTrue(listener.spriteFiles.isEmpty()); // not shown yet
        exec(state, "spbtn 6,6");
        exec(state, "btnwait %1");
        // But the button DOES register -- the image was loaded, just not displayed.
        assertEquals(java.util.Collections.singletonList(6), state.pendingChoiceButtonIds);
    }

    @Test
    public void vspRevealsALsphLoadedSpriteAtItsOriginalPosition() {
        NsExecState state = new NsExecState();
        exec(state, "lsph 6,\"dat\\system\\close.jpg\",100,200");
        exec(state, "vsp 6,1");
        assertEquals(new File(vnDir, "dat/system/close.jpg"),
                listener.spriteFiles.get(listener.spriteFiles.size() - 1));
        int[] xy = listener.spriteLayersXY.get(listener.spriteLayersXY.size() - 1);
        assertEquals(6, xy[0]);
        assertEquals(100, xy[1]);
        assertEquals(200, xy[2]);
    }

    @Test
    public void vspWithZeroHidesTheSprite() {
        NsExecState state = new NsExecState();
        exec(state, "lsp 6,\"dat\\system\\close.jpg\",100,200"); // shown immediately
        exec(state, "vsp 6,0");
        assertEquals(Integer.valueOf(6), listener.clearedLayers.get(listener.clearedLayers.size() - 1));
    }

    @Test
    public void vspOnALayerNeverLoadedIsANoOp() {
        NsExecState state = new NsExecState();
        exec(state, "vsp 6,1");
        assertTrue(listener.spriteFiles.isEmpty());
    }

    @Test
    public void spbtnOnALayerLoadedOnlyViaLsphStillRegistersTheButton() {
        // The scenario "spbtnOnALayerNeverLspdRegistersNoButtonAtAll" is guarding against: a real
        // "close menu" button is commonly "lsph"-loaded (image ready, but hidden) rather than "lsp",
        // and must still be clickable even before any "vsp" reveals it -- real ONScripter's own
        // spbtnCommand only checks whether an image was ever loaded (num_of_cells), never visibility.
        NsExecState state = new NsExecState();
        exec(state, "lsph 6,\"dat\\system\\close.jpg\",100,200");
        exec(state, "spbtn 6,6");
        exec(state, "btnwait %1");
        assertEquals(VnEngine.State.WAITING_CHOICE, state.runState);
        assertEquals(java.util.Collections.singletonList(6), state.pendingChoiceButtonIds);
    }

    @Test
    public void ldFiresOnSpriteAtAutoPositionIgnoringEffectId() {
        // Real "ld" syntax is "ld <l|c|r>,\"file\",<effect id>" -- the 3rd argument is a transition
        // effect, not a coordinate, and 'l'/'c'/'r' are
        // fixed left/center/right stand positions the host resolves against the decoded image's
        // own size (see VnEngine.Listener's AUTO_POSITION_* doc), not a numeric layer or a literal
        // coordinate this plain-Java engine layer could compute itself.
        NsExecState state = new NsExecState();
        exec(state, "ld c,face.png,3");
        assertEquals(NsCommandDispatcher.TACHI_LAYER_CENTER, listener.spriteLayersXY.get(0)[0]); // 'c' -> the fixed "center" slot
        assertEquals(VnEngine.Listener.AUTO_POSITION_CENTER, listener.spriteLayersXY.get(0)[1]);
        assertEquals(VnEngine.Listener.AUTO_POSITION_BOTTOM, listener.spriteLayersXY.get(0)[2]);
        assertEquals(new File(vnDir, "face.png"), listener.spriteFiles.get(0));
    }

    @Test
    public void ldAndClAgreeOnPositionSlotsSoClDoesNotCrossClearAnother() {
        NsExecState state = new NsExecState();
        exec(state, "ld l,left.png,3");
        exec(state, "ld c,center.png,3");
        exec(state, "cl c"); // clearing "center" must not also clear "left"
        assertEquals(Integer.valueOf(NsCommandDispatcher.TACHI_LAYER_CENTER), listener.clearedLayers.get(0));
    }

    @Test
    public void ldStripsFileLoadTag() {
        // ":a;name.png" is an alpha-blend load tag (commonly seen on
        // portrait sprites) with no on-disk representation of its own -- the real asset is just
        // "name.png".
        NsExecState state = new NsExecState();
        exec(state, "ld l,\":a;face.png\",3");
        assertEquals(new File(vnDir, "face.png"), listener.spriteFiles.get(0));
    }

    @Test
    public void ldUntaggedFileUsesTopLeftColorKey() {
        // In real ONScripter, an untagged "ld" image (e.g.
        // "ld l,\"poster.png\",3") defaults to TOPLEFT_KEY, not fully opaque.
        NsExecState state = new NsExecState();
        exec(state, "ld l,poster.png,3");
        assertEquals(VnEngine.SpriteTransparency.TOPLEFT_KEY, listener.spriteTransparencies.get(0));
    }

    @Test
    public void ldAlphaTaggedFileUsesAlphaMask() {
        NsExecState state = new NsExecState();
        exec(state, "ld c,\":a;kana2.png\",3");
        assertEquals(VnEngine.SpriteTransparency.ALPHA_MASK, listener.spriteTransparencies.get(0));
    }

    @Test
    public void alphaTagWithExtraEffectParametersIsStillRecognized() {
        // Real tags don't always put the type letter directly next to ';' -- extra slash-separated
        // effect params can come first, e.g. a title-screen text/button sprite:
        // "lsp 0,\":a/2,0,3;May/System/Title_Text.jpg\",0,0". Must still resolve to ALPHA_MASK, not
        // silently fall through to the untagged default.
        NsExecState state = new NsExecState();
        exec(state, "lsp 0,\":a/2,0,3;title_text.jpg\",0,0");
        assertEquals(VnEngine.SpriteTransparency.ALPHA_MASK, listener.spriteTransparencies.get(0));
        assertEquals(new File(vnDir, "title_text.jpg"), listener.spriteFiles.get(0));
    }

    @Test
    public void alphaTagCellCountIsParsedFromTheSlashParameter() {
        // A title-screen text sprite such as "lsp 0,\":a/2,0,3;
        // May/System/Title_Text.jpg\",0,0" decodes 1280 wide: genuinely 2 side-by-side 320+320
        // [color|mask] cells, not one plain 640+640 pair) -- the "2" must reach the listener so
        // ReaderActivity's compositor can split per-cell instead of splitting the whole image once.
        NsExecState state = new NsExecState();
        exec(state, "lsp 0,\":a/2,0,3;title_text.jpg\",0,0");
        assertEquals(Integer.valueOf(2), listener.spriteAlphaMaskCells.get(0));
    }

    @Test
    public void plainAlphaTagWithNoSlashParameterDefaultsToOneCell() {
        NsExecState state = new NsExecState();
        exec(state, "ld c,\":a;kana2.png\",3");
        assertEquals(Integer.valueOf(1), listener.spriteAlphaMaskCells.get(0));
    }

    @Test
    public void nonAlphaTagsDefaultToOneCellRegardlessOfSlashLikeContent() {
        NsExecState state = new NsExecState();
        exec(state, "ld l,poster.png,3");
        assertEquals(Integer.valueOf(1), listener.spriteAlphaMaskCells.get(0));
    }

    @Test
    public void bgHandlerAlwaysReportsOneAlphaMaskCellRegardlessOfAnyTag() {
        // Matches bgHandlerIsAlwaysOpaqueRegardlessOfAnyTag's own reasoning: real ONScripter also
        // forces "bg_info.num_of_cells = 1" unconditionally in ONScripter::createBackground.
        NsExecState state = new NsExecState();
        exec(state, "bg \":a/2,0,3;title_text.jpg\"");
        assertEquals(1, listener.lastBackgroundAlphaMaskCells);
    }

    @Test
    public void lspSpbtnBtnwaitOffersATextButtonGroupAsANativeChoiceMenu() {
        // ONScripter-EN's clickable-button-sprite pattern, commonly used for a
        // title screen -- this host can't render/hit-test real sprites, so it's mapped
        // onto the same native choice UI "select" already uses.
        NsExecState state = new NsExecState();
        exec(state, "lsp 1,\":s/36,38,0;#FFFFFF#a9a9a9`Start game\",565,430");
        exec(state, "lsp 2,\":s/36,38,0;#FFFFFF#a9a9a9`Continue game\",542,470");
        exec(state, "spbtn 1,1");
        exec(state, "spbtn 2,2");
        exec(state, "btnwait %1");
        assertEquals(java.util.Arrays.asList("Start game", "Continue game"), listener.lastChoices);
        assertEquals(VnEngine.State.WAITING_CHOICE, state.runState);
        assertEquals(Integer.valueOf(1), state.pendingBtnwaitVarIndex);
        assertEquals(java.util.Arrays.asList(1, 2), state.pendingChoiceButtonIds);
    }

    @Test
    public void btnwaitWithNoRegisteredButtonsStillBlocksForAPlainTap() {
        // Real ONScripter's "btnwait"/"selectbtnwait" always blocks waiting for a click, even with
        // zero registered buttons -- its own event loop never short-circuits just because its
        // button list is empty. Before this
        // was fixed, an empty button list made this a silent no-op that kept RUNNING instead of
        // blocking -- meaning the script would free-fall through whatever came next instead of ever
        // presenting anything to the player. See NsScriptEngine's own resumeFromTap() test for the
        // "resolves to -1" half of this.
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "btnwait %1");
        assertEquals(VnEngine.State.WAITING_TAP, state.runState);
        assertEquals(1, state.pendingBtnwaitVarIndex.intValue());
    }

    @Test
    public void selectBuildsPendingChoicesAndWaits() {
        NsExecState state = new NsExecState();
        exec(state, "select \"Go left\",*left,\"Go right\",*right");
        assertEquals(2, listener.lastChoices.size());
        assertEquals("Go left", listener.lastChoices.get(0));
        assertEquals(VnEngine.State.WAITING_CHOICE, state.runState);
        assertEquals("left", state.pendingChoiceLabels.get(0));
        assertEquals("right", state.pendingChoiceLabels.get(1));
    }

    @Test
    public void waveAndBgmResolveAssetsAndTildeStops() {
        NsExecState state = new NsExecState();
        exec(state, "wave click.wav");
        assertEquals(new File(vnDir, "click.wav"), listener.lastSound);
        assertEquals(1, listener.lastSoundTimes);
        exec(state, "waveloop amb.wav");
        assertEquals(-1, listener.lastSoundTimes);
        exec(state, "bgm theme.mp3");
        assertEquals(new File(vnDir, "theme.mp3"), listener.lastMusic);
        exec(state, "bgm ~");
        assertTrue(listener.musicStopped);
    }

    @Test
    public void playWithABareFilenamePlaysItDirectly() {
        // Real ONScripter-EN's "play"/"playonce" also accept a plain filename (its sequenced-MIDI-
        // music fallback) instead of a "*N" CD-track reference -- see NsCommandDispatcher's "play"
        // handler doc.
        NsExecState state = new NsExecState();
        exec(state, "play theme.mid");
        assertEquals(new File(vnDir, "theme.mid"), listener.lastMusic);
    }

    @Test
    public void playstopStopsMusic() {
        NsExecState state = new NsExecState();
        exec(state, "bgm theme.mp3");
        exec(state, "playstop");
        assertTrue(listener.musicStopped);
    }

    @Test
    public void playWithAStarTrackNumberResolvesToARealCdTrackFile() throws IOException {
        // "play \"*9\"" is real ONScripter-EN's CD-DA track command: without a real CD drive, it
        // falls back to a "cd\trackNN.mp3"/".ogg"/".wav" file (checked in that order -- see
        // NsCommandDispatcher's "play" handler doc). Uses a real temp dir (unlike this test class's
        // usual fake vnDir) since the handler only calls onMusic once it's confirmed the candidate
        // file actually exists.
        File realVnDir = tmp.newFolder("vn");
        File cdDir = new File(realVnDir, "cd");
        cdDir.mkdirs();
        File track = new File(cdDir, "track09.ogg");
        try (FileOutputStream out = new FileOutputStream(track)) {
            out.write(new byte[]{1, 2, 3});
        }

        NsExecState state = new NsExecState();
        NsCommandDispatcher.execute(NsTokenizer.classify("play \"*9\""), state, listener, realVnDir);
        assertEquals(track, listener.lastMusic);
    }

    @Test
    public void playWithAStarTrackNumberThatHasNoMatchingFileDoesNotCallOnMusic() throws IOException {
        File realVnDir = tmp.newFolder("vn2");
        NsExecState state = new NsExecState();
        NsCommandDispatcher.execute(NsTokenizer.classify("play \"*9\""), state, listener, realVnDir);
        assertNull(listener.lastMusic);
        assertFalse(listener.musicStopped);
    }

    @Test
    public void mp3AndItsVariantsAllPlayMusicJustLikeBgm() {
        // Real ONScripter-EN binds "bgm"/"bgmonce"/"mp3"/"mp3loop"/"mp3save" ALL to the exact same
        // command -- they only differ in a
        // looping flag this host's single onMusic(File) callback has no equivalent for. Some
        // scripts use "mp3loop"/"mp3" exclusively and never call "bgm" at all; before these
        // aliases existed, background music using those mnemonics never played.
        NsExecState state = new NsExecState();
        for (String cmd : new String[]{"mp3", "mp3loop", "mp3save", "bgmonce"}) {
            exec(state, cmd + " theme.mp3");
            assertEquals(cmd, new File(vnDir, "theme.mp3"), listener.lastMusic);
        }
    }

    @Test
    public void bgmResolvesAFilenameStoredInAStringVariable() {
        // A "$var" file argument must resolve to the variable's actual stored VALUE, not the bare
        // variable NAME text -- a common pattern is a "*bgm" wrapper subroutine
        // that receives its real filename via "getparam" into "$SoundFileName" and passes it on as
        // "_bgm $SoundFileName". Before this was fixed, resolveFileArg returned arg.value unevaluated
        // for a STR_VAR_EXPR, i.e. the literal text "SoundFileName", so every asset load funneled
        // through a wrapper like this resolved to a nonexistent path named after the variable itself.
        NsExecState state = new NsExecState();
        state.strVars.put(5, "theme.mp3");
        exec(state, "numalias SoundFileName,5");
        exec(state, "bgm $SoundFileName");
        assertEquals(new File(vnDir, "theme.mp3"), listener.lastMusic);
    }

    @Test
    public void waitOnlyPausesWhenDelaysEnabled() {
        NsExecState state = new NsExecState();
        state.delaysEnabled = false;
        exec(state, "wait 500");
        assertEquals(-1, listener.lastDelayFrames);
        assertEquals(VnEngine.State.FINISHED, state.runState); // unchanged default

        state.delaysEnabled = true;
        exec(state, "wait 500");
        assertEquals(30, listener.lastDelayFrames); // 500ms * 60/1000
        assertEquals(VnEngine.State.WAITING_DELAY, state.runState);
    }

    @Test
    public void inlineWaitCodeIsARealDelayNotLiteralDialogue() {
        // "!w2000"/"!s500" etc. are real ONScripter inline text-embedded control codes, not
        // commands or plain dialogue --
        // scripts commonly use "!w2000"/"!w500" as whole standalone lines to pace a
        // company-logo intro. Before this was recognized, such a line fell through as plain
        // dialogue and was shown verbatim on screen, permanently (it has no '\\'/'@' of its own to
        // stop waiting on).
        NsExecState state = new NsExecState();
        state.delaysEnabled = true;
        exec(state, "!w2000");
        assertEquals(120, listener.lastDelayFrames); // 2000ms * 60/1000
        assertEquals(VnEngine.State.WAITING_DELAY, state.runState);
        assertTrue("must never be shown as dialogue", listener.textLines.isEmpty());
    }

    @Test
    public void inlineWaitCodeIsANoOpWhenDelaysAreDisabled() {
        NsExecState state = new NsExecState();
        state.delaysEnabled = false;
        state.runState = VnEngine.State.RUNNING;
        exec(state, "!w2000");
        assertEquals(VnEngine.State.RUNNING, state.runState);
        assertTrue(listener.textLines.isEmpty());
    }

    @Test
    public void inlineTextSpeedCodeIsASilentNoOp() {
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "!s500");
        assertEquals(VnEngine.State.RUNNING, state.runState);
        exec(state, "!sd");
        assertEquals(VnEngine.State.RUNNING, state.runState);
        assertTrue(listener.textLines.isEmpty());
    }

    @Test
    public void inlineTextSpeedCodeWithTrailingTextShowsTheRemainderAsDialogue() {
        // A real, observed pattern (the_poor_little_bird's own script): "!s100/" -- real
        // ONScripter's own digit-reading loop for "!s<N>" stops at the first non-digit character
        // and keeps reading/displaying whatever comes after as ordinary text; it never requires the
        // code to be the WHOLE line. Before this was recognized, requiring an exact whole-line
        // match rejected the match entirely, showing the literal "!s100/" text instead of setting
        // the pace (a no-op here either way) and displaying the real trailing "/".
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "!s100/");
        assertEquals(java.util.Collections.singletonList("/"), listener.textLines);
    }

    @Test
    public void bareInlineDelayCodeAloneOnALineIsARealNoOp() {
        // "!d" (no digits, unlike "!w<N>") has no host surface either way -- it must still be
        // recognized as the real code (not shown as literal "!d" dialogue) when used the normal
        // way real scripts do: alone, its own whole tag.
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "!d");
        assertEquals(VnEngine.State.RUNNING, state.runState);
        assertTrue(listener.textLines.isEmpty());
    }

    @Test
    public void dialogueStartingWithBangDLetterIsNeverMistakenForTheBareDelayCode() {
        // The zero-digit "!d" code has no digit run of its own to bound where it ends -- unlike
        // "!s100/", which stops unambiguously at the first non-digit -- so a widened regex that
        // let ANY trailing text follow "!d" the same way silently swallowed the "!d"/"!D" off the
        // front of ordinary dialogue that just happens to start that way, showing a truncated
        // remainder instead of the real line.
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "!Dad, look out!");
        assertEquals(java.util.Collections.singletonList("!Dad, look out!"), listener.textLines);
    }

    @Test
    public void dialogueStartingWithBangDLowercaseWordIsAlsoNeverMistaken() {
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "!dark");
        assertEquals(java.util.Collections.singletonList("!dark"), listener.textLines);
    }

    @Test
    public void bareInlineTextSpeedDefaultCodeIsNeverMistakenEitherWhenGluedToLetters() {
        // "!sd" has the exact same zero-digit ambiguity as "!d" -- same fix, same guard.
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "!sdeath");
        assertEquals(java.util.Collections.singletonList("!sdeath"), listener.textLines);
    }

    @Test
    public void btnRegistersAClickableButtonJustLikeSpbtn() {
        // The original, simplest button-registration idiom -- a rectangular region cropped from the
        // single "btndef"-loaded
        // image, distinct from "spbtn"/"exbtn" which tag a numbered sprite layer. A typical
        // main menu ("Start"/"Continue"/"Load") registers its buttons exactly this way.
        NsExecState state = new NsExecState();
        exec(state, "btn 1,232,517,98,40,232,517");
        exec(state, "btn 2,347,518,94,41,347,518");
        exec(state, "btnwait2 %0");
        assertEquals(VnEngine.State.WAITING_CHOICE, state.runState);
        assertEquals(java.util.Arrays.asList(1, 2), state.pendingChoiceButtonIds);
    }

    @Test
    public void btnWithoutAnyBtndefLoadedGetsNoImageOrCropJustTheGenericLabel() {
        // The pre-existing fallback for a game that uses "btn" without ever calling "btndef" (or
        // after "btndef clear") -- there's genuinely nothing to crop from, so this must stay a
        // plain, imageless "Button N" entry rather than crash or fabricate a crop rectangle.
        NsExecState state = new NsExecState();
        exec(state, "btn 1,232,517,98,40,232,517");
        exec(state, "btnwait2 %0");
        assertEquals(java.util.Collections.singletonList("Button 1"), listener.lastChoices);
        assertEquals(java.util.Collections.singletonList((File) null), listener.lastChoiceImages);
        assertEquals(java.util.Collections.singletonList((int[]) null), listener.lastChoiceImageCropRects);
    }

    @Test
    public void btnAfterBtndefCropsItsOwnRectangleOutOfTheSharedButtonSheetImage() {
        // The real fix this test guards: a plain "btn"-only menu (a common pattern for a game's own
        // title/system-menu chrome, e.g. Kagetsu Tohya's own -- see NsCommandDispatcher's "btn"
        // handler doc) has no "spbtn"-style per-layer sprite of its own at all -- its ENTIRE visible
        // appearance is a crop of the single "btndef"-loaded image, at its own declared
        // (srcX,srcY,w,h). Before "btndef" was implemented, every such button fell back to a bare
        // "Button N" placeholder with no image whatsoever, regardless of how the real game actually
        // looks.
        NsExecState state = new NsExecState();
        exec(state, "btndef \"dat\\system\\amenu.bmp\"");
        exec(state, "btn 1,12,405,150,34,12,405");
        exec(state, "btn 2,178,405,150,34,178,405");
        exec(state, "btnwait2 %0");
        assertEquals(2, listener.lastChoiceImages.size());
        File expectedImage = listener.lastChoiceImages.get(0);
        assertEquals(expectedImage, listener.lastChoiceImages.get(1));
        assertArrayEquals(new int[]{12, 405, 150, 34}, listener.lastChoiceImageCropRects.get(0));
        assertArrayEquals(new int[]{178, 405, 150, 34}, listener.lastChoiceImageCropRects.get(1));
    }

    @Test
    public void btndefClearStopsFurtherBtnButtonsFromCroppingTheOldImage() {
        NsExecState state = new NsExecState();
        exec(state, "btndef \"dat\\system\\amenu.bmp\"");
        exec(state, "btndef clear");
        exec(state, "btn 1,12,405,150,34,12,405");
        exec(state, "btnwait2 %0");
        assertEquals(java.util.Collections.singletonList((File) null), listener.lastChoiceImages);
        assertEquals(java.util.Collections.singletonList((int[]) null), listener.lastChoiceImageCropRects);
    }

    @Test
    public void unrecognizedLowercaseCommandIsSilentlySkippedNotShownAsDialogue() {
        // Real scripts routinely invoke commands/defsub pseudo-commands far outside this core
        // subset -- an unrecognized lowercase-leading
        // token must be silently skipped, never misread as prose.
        NsExecState state = new NsExecState();
        exec(state, "konnichiwa sensei");
        assertTrue(listener.textLines.isEmpty());
    }

    @Test
    public void capitalizedLineIsAlwaysDialogueEvenIfItLooksLikeACommand() {
        NsExecState state = new NsExecState();
        exec(state, "Wait, no!");
        assertEquals("Wait, no!", listener.textLines.get(0));
        assertEquals(-1, listener.lastDelayFrames); // the "wait" handler never ran
    }

    @Test
    public void dialoguePagewaitMarkerWaitsAndFlagsPageClear() {
        NsExecState state = new NsExecState();
        exec(state, "Goodbye\\");
        assertEquals("Goodbye", listener.textLines.get(0));
        assertEquals(VnEngine.State.WAITING_TAP, state.runState);
        assertTrue(state.pendingPageClearOnResume);
    }

    @Test
    public void dialogueAtMarkerWaitsWithoutPageClear() {
        // The wait-but-keep-page marker is a trailing '@' (see NsDialogue's class doc), not a
        // leading one.
        NsExecState state = new NsExecState();
        exec(state, "Still reading@");
        assertEquals("Still reading", listener.textLines.get(0));
        assertEquals(VnEngine.State.WAITING_TAP, state.runState);
        assertFalse(state.pendingPageClearOnResume);
    }

    @Test
    public void midLineMarkerPausesAndQueuesTheRestForTheNextResume() {
        // Real scripts commonly do things like "...good quality.@ Nevertheless, it's still
        // small.@" to pause mid-sentence, not just at the line's own end.
        NsExecState state = new NsExecState();
        exec(state, "First part@ second part@");
        assertEquals("First part", listener.textLines.get(0));
        assertEquals(VnEngine.State.WAITING_TAP, state.runState);
        assertFalse(state.pendingPageClearOnResume);
        assertEquals(" second part@", state.pendingDialogueRemainder); // its own trailing '@' still unconsumed
    }

    @Test
    public void aBareStringVariableLineShowsItsResolvedValueNotTheLiteralReference() {
        // A real, common NScripter name-tag idiom (plain_song_christmas_special's own script): the
        // author sets a string variable once ("mov $1,\"Ryuuji\"") then uses that same bare "$1" as
        // its own whole line right before each of that character's dialogue lines -- combined with
        // a "setwindow" carving out a separate name-box region, this is how the name tag renders at
        // all. Before this was evaluated, such a line showed the literal, unresolved "$1" text.
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "mov $1,\"Ryuuji\"");
        exec(state, "$1");
        assertEquals(java.util.Collections.singletonList("Ryuuji"), listener.textLines);
    }

    @Test
    public void aWholeLineThatLooksLikeABareStringVariableButWasNeverAssignedStaysLiteral() {
        // "$5" has the exact same shape as a real name-tag reference (see the test above), but a
        // literal price/code line like this was never assigned via "mov"/"stralias" first -- it
        // must be shown as-is, not silently resolved to strVars' own "" default for an unset slot
        // (or, worse, some unrelated earlier value that slot happens to hold).
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "$5");
        assertEquals(java.util.Collections.singletonList("$5"), listener.textLines);
    }

    @Test
    public void aDollarSignEmbeddedMidSentenceIsLeftAsLiteralText() {
        // Deliberately narrow: only a line that's NOTHING BUT the bare reference gets resolved --
        // '$' appearing mid-sentence (e.g. an incidental price mentioned in prose) is not a pattern
        // this handles, to avoid misreading ordinary dialogue.
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "It costs $1 at the store.");
        assertEquals(java.util.Collections.singletonList("It costs $1 at the store."), listener.textLines);
    }

    @Test
    public void inlineColorCodeIsStrippedNotShownAsLiteralText() {
        // Real NScripter/ONScripter dialogue can embed a "#RRGGBB" inline control code ANYWHERE to
        // change the current text color (see ONScripterLabel_text.cpp's "ch == '#'" branch in the
        // real source) -- it consumes exactly 6 hex digits and produces no visible characters of
        // its own. A real, observed case (my_black_cat's own opening line): a WHOLE line consisting
        // of nothing but "#ffffff" right after a screen-clearing "bg black" transition, meant only
        // to set the color white for the dialogue that follows. Before this was stripped, nothing
        // in this pipeline treated '#' as special, so it printed as literal "#ffffff" dialogue.
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "#ffffff");
        assertTrue("a color-code-only line produces no visible text", listener.textLines.isEmpty());

        exec(state, "#ff0000Hello there");
        assertEquals("Hello there", listener.textLines.get(0));
    }

    @Test
    public void aHashNotFollowedBySixHexDigitsIsLiteralText() {
        // Real ONScripter's own "#" handling only treats it as a color code when EXACTLY 6 valid
        // hex digits immediately follow -- anything else (too few digits, non-hex characters) falls
        // through as an ordinary literal '#' character instead.
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "#1 best friend");
        assertEquals("#1 best friend", listener.textLines.get(0));
    }

    @Test
    public void plainDialogueAutoContinuesWithoutWaiting() {
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "Just a caption");
        assertEquals("Just a caption", listener.textLines.get(0));
        assertEquals(VnEngine.State.RUNNING, state.runState); // untouched: no wait triggered
    }

    @Test
    public void aStrayLeadingColonAfterAnIfConditionIsSkippedNotShownAsDialogue() {
        // A real, observed pattern (night_of_the_forget_me_nots' own title-menu loop):
        // "if %10<=0 :goto *title2" -- a stray colon sits right after the condition, before the
        // real consequent command. Real ONScripter's own ifCommand just returns RET_CONTINUE and
        // lets the ordinary dispatch loop read whatever comes next (colon included) the same way it
        // tolerates an empty segment between two chained commands -- so "goto" still runs. Before
        // this was handled, the leading, non-lowercase ':' made the whole consequent ("...goto
        // *title2") get misread as one literal dialogue line, so the goto never fired at all --
        // a menu loop relying on it to return to the title screen on an empty click got stuck
        // showing raw ":goto *title2" text forever instead.
        NsExecState state = new NsExecState();
        state.labelIndex.put("title2", 500);
        state.numVars.put(10, 0L);
        state.pc = 5;
        exec(state, "if %10<=0 :goto *title2");
        assertEquals(500, state.pc);
        assertTrue("must not show the raw chain syntax as dialogue", listener.textLines.isEmpty());
    }

    @Test
    public void ifAcceptsBareEqualsAsSynonymForDoubleEquals() {
        // Real scripts commonly write "if %29 = 1 ..." (single '=', spaces around it).
        NsExecState state = new NsExecState();
        state.numVars.put(29, 1L);
        exec(state, "if %29 = 1 mov %2,99");
        assertEquals(Long.valueOf(99), state.numVars.get(2));
    }

    @Test
    public void ifOperatorMayBePaddedWithSpaces() {
        // Real scripts commonly write "if %133 == 0 ..." (spaces around "==").
        NsExecState state = new NsExecState();
        state.numVars.put(133, 0L);
        exec(state, "if %133 == 0 mov %2,99");
        assertEquals(Long.valueOf(99), state.numVars.get(2));
    }

    @Test
    public void colonChainsMultipleStatementsOnOneLine() {
        // Real scripts commonly chain like "dwave 1,\"x.wav\":gosub *foo:goto *bar".
        NsExecState state = new NsExecState();
        state.labelIndex.put("bar", 9);
        exec(state, "mov %1,1:mov %2,2:goto *bar");
        assertEquals(Long.valueOf(1), state.numVars.get(1));
        assertEquals(Long.valueOf(2), state.numVars.get(2));
        assertEquals(9, state.pc);
    }

    @Test
    public void colonChainStopsAfterABlockingStatement() {
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "mov %1,1:Blocking line\\:mov %2,2");
        assertEquals(Long.valueOf(1), state.numVars.get(1));
        assertEquals(VnEngine.State.WAITING_TAP, state.runState);
        assertNull(state.numVars.get(2)); // never reached: chain stopped at the pagewait
    }

    @Test
    public void ifConsequentCanBeAColonChain() {
        NsExecState state = new NsExecState();
        state.numVars.put(1, 1L);
        state.labelIndex.put("done", 5);
        exec(state, "if %1==1 mov %2,7:goto *done");
        assertEquals(Long.valueOf(7), state.numVars.get(2));
        assertEquals(5, state.pc);
    }

    @Test
    public void trailingSemicolonCommentIsStrippedBeforeParsingArgs() {
        // Real scripts commonly trail a command with an unquoted ";comment"
        // with no separating whitespace, e.g. dwave 1,"se.wav";<comment>.
        NsExecState state = new NsExecState();
        exec(state, "wave click.wav;this is a trailing comment");
        assertEquals(new File(vnDir, "click.wav"), listener.lastSound);
    }

    @Test
    public void semicolonInsideAQuotedArgumentIsNotTreatedAsAComment() {
        NsExecState state = new NsExecState();
        exec(state, "bg \"we;ird.png\"");
        assertEquals(new File(vnDir, "we;ird.png"), listener.lastBackground);
    }

    @Test
    public void dwaveResolvesFileIgnoringTheLeadingChannelArgument() {
        NsExecState state = new NsExecState();
        exec(state, "dwave 1,\"click.wav\"");
        assertEquals(new File(vnDir, "click.wav"), listener.lastSound);
    }

    @Test
    public void dwaveWithTrailingTabTextShowsTheTextAndPlaysTheSound() {
        NsExecState state = new NsExecState();
        exec(state, "dwave 0,\"click.wav\"\tSome text");
        assertEquals(new File(vnDir, "click.wav"), listener.lastSound);
        assertEquals(1, listener.textLines.size());
        assertEquals("Some text", listener.textLines.get(0));
    }

    @Test
    public void dwaveWithNumericThirdArgumentIsNotMisreadAsText() {
        NsExecState state = new NsExecState();
        exec(state, "dwave 0,\"click.wav\",1");
        assertEquals(new File(vnDir, "click.wav"), listener.lastSound);
        assertTrue(listener.textLines.isEmpty());
    }

    @Test
    public void dwavestopStopsSound() {
        NsExecState state = new NsExecState();
        exec(state, "dwavestop 0");
        assertNull(listener.lastSound);
        assertEquals(1, listener.lastSoundTimes);
    }

    @Test
    public void mp3fadeoutStopsMusic() {
        NsExecState state = new NsExecState();
        exec(state, "mp3fadeout");
        assertTrue(listener.musicStopped);
    }

    @Test
    public void bgSkipsColorTokensInsteadOfTreatingThemAsFilenames() {
        // Real scripts commonly write "bg #FFFFFF,10,2000" / "bg white,10,3000".
        NsExecState state = new NsExecState();
        exec(state, "bg #FFFFFF,10,2000");
        assertNull(listener.lastBackground);
        exec(state, "bg white,10,3000");
        assertNull(listener.lastBackground);
    }

    @Test
    public void brAndCrEmitBlankLineWithoutWaiting() {
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "br");
        exec(state, "cr");
        assertEquals(2, listener.textLines.size());
        assertEquals("", listener.textLines.get(0));
        assertEquals(VnEngine.State.RUNNING, state.runState);
    }

    @Test
    public void resetJumpsToStartPcAndClearsLocalState() {
        // A common pattern is a "*check_reset" confirm dialog whose "Yes" branch
        // ends with a bare "reset" -- real NScripter's "simulate a fresh launch" command.
        NsExecState state = new NsExecState();
        state.startPc = 7;
        state.numVars.put(1, 42L);
        state.strVars.put(2, "x");
        state.callStack.push(3);
        state.pendingButtons.add(new NsExecState.ButtonEntry("Yes", 144, null,
                VnEngine.SpriteTransparency.OPAQUE, 1, null, NsExecState.ButtonEntry.Source.SPBTN));
        exec(state, "reset");
        assertEquals(7, state.pc);
        assertTrue(state.numVars.isEmpty());
        assertTrue(state.strVars.isEmpty());
        assertTrue(state.callStack.isEmpty());
        assertTrue(state.pendingButtons.isEmpty());
    }

    @Test
    public void resetAlsoClearsThePendingButtonImageAndCselButtonLists() {
        // Before this was fixed, "reset" cleared pendingButtonLabels/pendingButtonIds but left the
        // newer parallel pendingButtonImage*/pendingCselButton*/lastChoiceImage* lists untouched --
        // a confirm-dialog "Yes -> reset" idiom firing while spbtn-registered image buttons (or a
        // pending csel choice) were in flight left those lists desynced index-for-index with the
        // labels/ids that DID get cleared, so the next spbtn/btnwait zipped new labels against
        // stale, wrong images. Now backed by one NsExecState.pendingButtons list, so this can only
        // ever desync with itself -- kept as a regression test for the underlying behavior anyway.
        NsExecState state = new NsExecState();
        state.startPc = 7;
        exec(state, "lsp 1,\":a/2,0,3;May\\System\\Button_for_Title_Text.jpg\",74,274");
        exec(state, "spbtn 1,1");
        exec(state, "csel \"Coffee\",*coffee");
        exec(state, "cselbtn 0,150,34,40");
        exec(state, "reset");
        assertTrue(state.pendingButtons.isEmpty());
        assertNull(state.lastChoiceImages);
        assertNull(state.lastChoiceImageTransparencies);
        assertNull(state.lastChoiceImageAlphaMaskCells);
        assertNull(state.lastChoiceImageCropRects);

        // The next spbtn registration after reset must start from a clean slate, not append onto
        // stale leftovers.
        exec(state, "lsp 1,\":a/2,0,3;May\\System\\Button_for_Title_Text.jpg\",74,274");
        exec(state, "spbtn 1,9");
        exec(state, "btnwait %0");
        assertEquals(1, state.pendingChoiceButtonIds.size());
        assertEquals(9, state.pendingChoiceButtonIds.get(0).intValue());
    }

    @Test
    public void endExitsToLibraryAndHaltsExecution() {
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "end");
        assertTrue(listener.exitedToLibrary);
        assertEquals(VnEngine.State.FINISHED, state.runState);
    }

    @Test
    public void systemcallLoadOpensLoadMenuAndPausesExecution() {
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "systemcall load");
        assertTrue(listener.loadMenuRequested);
        assertEquals(VnEngine.State.WAITING_TAP, state.runState);
    }

    @Test
    public void systemcallWithOtherSubcommandNoOps() {
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "systemcall save");
        assertFalse(listener.loadMenuRequested);
        assertEquals(VnEngine.State.RUNNING, state.runState);
    }

    @Test
    public void defsubThenBarewordCallJumpsAndQueuesParams() {
        // A common pattern is a "*sys_define" block ("defsub change_b") and its
        // later bareword call sites ("change_b \"foo\"").
        NsExecState state = new NsExecState();
        state.labelIndex.put("change_b", 10);
        state.pc = 3;
        exec(state, "defsub change_b");
        exec(state, "change_b \"foo\"");
        assertEquals(10, state.pc);
        assertEquals(1, state.callStack.size());
        assertEquals(1, state.pendingSubParams.size());
        assertEquals("foo", state.pendingSubParams.get(0).value);
        state.pc = 20;
        exec(state, "return");
        assertEquals(3, state.pc);
    }

    @Test
    public void getparamAssignsStringAndNumericPositionally() {
        NsExecState state = new NsExecState();
        state.labelIndex.put("mysub", 10);
        exec(state, "defsub mysub");
        exec(state, "mysub \"bar\",5");
        exec(state, "getparam $24,%3");
        assertEquals("bar", state.strVars.get(24));
        assertEquals(Long.valueOf(5), state.numVars.get(3));
    }

    @Test
    public void getparamWithFewerCallArgsThanVarsLeavesRestUntouched() {
        NsExecState state = new NsExecState();
        state.labelIndex.put("mysub", 10);
        exec(state, "defsub mysub");
        exec(state, "mysub \"onlyone\"");
        state.numVars.put(3, 999L);
        exec(state, "getparam $24,%3");
        assertEquals("onlyone", state.strVars.get(24));
        assertEquals(Long.valueOf(999), state.numVars.get(3)); // untouched: no 2nd call arg
    }

    @Test
    public void underscorePrefixInvokesTheTrueNativeCommandBypassingAnyDefsubOfTheSameName() {
        // ONScripter-EN's escape hatch for calling a command's TRUE native implementation from
        // inside a "defsub" wrapper of that same bare name -- e.g. a "*bgm" subroutine
        // (defsub-registered to shadow the native "bgm"), which finishes
        // by calling "_bgm $SoundFileName" to invoke the real thing. Before this was recognized, an
        // underscore isn't a lowercase letter, so the ordinary command-vs-dialogue gate rejected the
        // whole line and it was shown as literal garbage dialogue instead of ever running.
        NsExecState state = new NsExecState();
        exec(state, "_bgm theme.mp3");
        assertEquals(new File(vnDir, "theme.mp3"), listener.lastMusic);
        assertTrue("should never be treated as dialogue", listener.textLines.isEmpty());
    }

    @Test
    public void underscorePrefixIgnoresAnyDefsubRegisteredForTheSameName() {
        // The whole point of the underscore is to bypass a "defsub" override of the same bare name
        // -- "_csp" must still resolve straight to the native "csp" handler even when a script has
        // separately defsub'd "csp" itself.
        NsExecState state = new NsExecState();
        state.labelIndex.put("csp", 10);
        exec(state, "defsub csp");
        exec(state, "_csp 2");
        assertEquals(java.util.Collections.singletonList(2), listener.clearedLayers);
        assertTrue(state.callStack.isEmpty()); // never dispatched as the defsub'd pseudo-command
    }

    @Test
    public void underscorePrefixForAnUnknownNativeCommandIsANoOp() {
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "_notarealcommand 1,2,3");
        assertEquals(VnEngine.State.RUNNING, state.runState);
        assertTrue(listener.textLines.isEmpty());
    }

    @Test
    public void btnwait2AndTextbtnwaitBehaveLikeBtnwait() {
        // Real ONScripter-EN binds "btnwait"/"btnwait2"/"textbtnwait"/"selectbtnwait" ALL to the
        // exact same command body -- they
        // only differ in cosmetic internal flags, never in blocking behavior. "btnwait2" in
        // particular is commonly used heavily by a right-click
        // system menu's own button-wait loop; before this was recognized, it silently no-op'd
        // instead of blocking, so that menu's click was never actually captured.
        NsExecState state = new NsExecState();
        exec(state, "lsp 1,\":s/36,38,0;#FFFFFF`Option\",565,430");
        exec(state, "spbtn 1,7");
        exec(state, "btnwait2 %1");
        assertEquals(VnEngine.State.WAITING_CHOICE, state.runState);
        assertEquals(java.util.Collections.singletonList(7), state.pendingChoiceButtonIds);

        NsExecState state2 = new NsExecState();
        exec(state2, "lsp 1,\":s/36,38,0;#FFFFFF`Option\",565,430");
        exec(state2, "spbtn 1,7");
        exec(state2, "textbtnwait %1");
        assertEquals(VnEngine.State.WAITING_CHOICE, state2.runState);
    }

    @Test
    public void exbtnRegistersAClickableButtonJustLikeSpbtn() {
        // "exbtn" is commonly what a title screen uses to register
        // its "Start"/"Continue"/"Option"/"End" buttons -- in real ONScripter-EN, it feeds the
        // exact same button-click list "spbtn" does, just with
        // an extra hitmask spec argument this host can't hit-test anyway (ignored, same tolerance
        // "spbtn"'s own coordinates get). "exbtn_d" is a real-time hit-test control declaration with
        // no equivalent this host needs -- a safe no-op.
        NsExecState state = new NsExecState();
        exec(state, "lsp 201,\"menu\\\\bt_start.bmp\",0,0");
        exec(state, "exbtn_d \"P201C206\"");
        exec(state, "exbtn 201,1,\"M206,273,248\"");
        exec(state, "btnwait2 %1");
        assertEquals(VnEngine.State.WAITING_CHOICE, state.runState);
        assertEquals(java.util.Collections.singletonList(1), state.pendingChoiceButtonIds);
    }

    @Test
    public void selectbtnwaitBehavesLikeBtnwait() {
        // "cselbtn" pulls its label from the real select-link list "csel" declares -- NOT any
        // "lsp"-based sprite text
        // label, which is a different, unrelated idiom (see "spbtn"'s own doc).
        NsExecState state = new NsExecState();
        exec(state, "csel \"Option 1\",*opt1");
        exec(state, "cselbtn 0,144,0,0");
        exec(state, "selectbtnwait %1");
        assertEquals(java.util.Collections.singletonList("Option 1"), listener.lastChoices);
        assertEquals(VnEngine.State.WAITING_CHOICE, state.runState);
        assertEquals(java.util.Collections.singletonList(144), state.pendingChoiceButtonIds);
    }

    @Test
    public void realCselChoiceIsShownAloneEvenWithASystemToolbarRegisteredAlongsideIt() {
        // A real, very common pattern: a persistent system toolbar (quick-save/quick-load/menu/
        // backlog/skip/auto/help icons) is registered via plain "spbtn" from a shared subroutine
        // gosub'd right before nearly every blocking wait in a script, INCLUDING right before a
        // real "csel"-declared narrative choice's own "selectbtnwait" (see
        // NsExecState.pendingCselButtonLabels's doc). Before this was fixed, both groups fed the
        // same flat choice list, so a real 2-option decision like "Coffee" vs "Sports drink" showed
        // up buried among 8+ unrelated toolbar buttons with no way to tell which was which.
        NsExecState state = new NsExecState();
        exec(state, "lsp 111,\":s/12,12,0;#8888aa#FFFF66Q.SAVE\",220,450");
        exec(state, "spbtn 111,116"); // toolbar button, NOT part of the real choice
        exec(state, "csel \"Coffee\",*coffee,\"Sports drink\",*sports");
        exec(state, "cselbtn 0,150,34,40");
        exec(state, "cselbtn 1,151,34,64");
        exec(state, "selectbtnwait %1");
        assertEquals(java.util.Arrays.asList("Coffee", "Sports drink"), listener.lastChoices);
        assertEquals(java.util.Arrays.asList(150, 151), state.pendingChoiceButtonIds);
    }

    @Test
    public void plainSpbtnMenuStillWorksWhenNoCselChoiceIsPending() {
        // The title screen's own "hajime"/"tuduki"/"syuuryou" menu, say -- no "csel" involved at
        // all -- must still work exactly as before: real ONScripter-compliant behavior for THAT
        // screen is showing the plain "spbtn" list, not an empty one.
        NsExecState state = new NsExecState();
        exec(state, "lsp 49,\":a/2,0,3;dat\\menu\\hajime.jpg\",410,80");
        exec(state, "spbtn 49,49");
        exec(state, "btnwait %1");
        assertEquals(java.util.Collections.singletonList("hajime"), listener.lastChoices);
    }

    @Test
    public void spbtnButtonsSharingOneFallbackLabelGetDisambiguatedWithAPositionalSuffix() {
        // A real pattern (May Sky's own title menu): several "spbtn" buttons all "lsp"-load the
        // exact SAME shared placeholder/highlight-only image at different screen positions, with
        // the real "Start"/"Load"/"Extra"-style art baked into a separate sprite this host has no
        // way to associate back to any one button (see spbtnHandler's own doc on the fileNameHint
        // fallback). Before disambiguateDuplicateLabels existed, all three buttons showed the
        // literal same text ("Button_for_Title_Text") with nothing at all to tell them apart.
        NsExecState state = new NsExecState();
        exec(state, "lsp 1,\":a/2,0,3;May\\System\\Button_for_Title_Text.jpg\",74,274");
        exec(state, "lsp 2,\":a/2,0,3;May\\System\\Button_for_Title_Text.jpg\",74,332");
        exec(state, "lsp 3,\":a/2,0,3;May\\System\\Button_for_Title_Text.jpg\",74,388");
        exec(state, "spbtn 1,1");
        exec(state, "spbtn 2,2");
        exec(state, "spbtn 3,3");
        exec(state, "btnwait %0");
        assertEquals(java.util.Arrays.asList(
                        "Button_for_Title_Text (1)", "Button_for_Title_Text (2)", "Button_for_Title_Text (3)"),
                listener.lastChoices);
    }

    @Test
    public void selectDisambiguatesTwoIdenticalOptionTextsTheSameWaySpbtnDoes() {
        // "select"/"selgosub" option text is normally real script-authored text, so this rarely
        // fires -- but a script can genuinely author (or variable-substitute into) two identical
        // option strings, and the player deserves the exact same "(N)" disambiguation an
        // spbtn-derived placeholder collision already gets, not two indistinguishable buttons just
        // because the source of the text was different.
        NsExecState state = new NsExecState();
        exec(state, "select \"Yes\",*yes1,\"Yes\",*yes2");
        assertEquals(java.util.Arrays.asList("Yes (1)", "Yes (2)"), listener.lastChoices);
        // Jump targets must stay aligned by index with the (now-suffixed) option text.
        assertEquals("yes1", state.pendingChoiceLabels.get(0));
        assertEquals("yes2", state.pendingChoiceLabels.get(1));
    }

    @Test
    public void selgosubDisambiguatesTwoIdenticalOptionTextsTheSameWaySelectDoes() {
        NsExecState state = new NsExecState();
        exec(state, "selgosub \"Yes\",*yes1,\"Yes\",*yes2");
        assertEquals(java.util.Arrays.asList("Yes (1)", "Yes (2)"), listener.lastChoices);
        assertEquals("yes1", state.pendingChoiceLabels.get(0));
        assertEquals("yes2", state.pendingChoiceLabels.get(1));
    }

    @Test
    public void spbtnButtonImageCarriesTheSameRealTransparencyTagItsOwnLspLoadHad() {
        // A button's image is just the layer's own "lsp"-loaded sprite (see spbtn's own doc) -- it
        // must carry the SAME real transparency treatment (here, a 2-cell ":a/2,0,3;" alpha mask,
        // the actual real-world tag on a_dream_of_summer's own title-screen "hajime" button) a host
        // rendering it would need, not get shown as a raw, untreated rectangle. Before
        // NsExecState.pendingButtonImageTransparencies existed, onChoices had no way to carry this
        // at all -- every button image was implicitly OPAQUE regardless of its real tag.
        NsExecState state = new NsExecState();
        exec(state, "lsp 49,\":a/2,0,3;dat\\menu\\hajime.jpg\",410,80");
        exec(state, "spbtn 49,49");
        exec(state, "btnwait %1");
        assertEquals(java.util.Collections.singletonList(VnEngine.SpriteTransparency.ALPHA_MASK),
                listener.lastChoiceImageTransparencies);
        assertEquals(java.util.Collections.singletonList(2), listener.lastChoiceImageAlphaMaskCells);
    }

    @Test
    public void cselbtnFeedsThePendingChoiceMenu() {
        NsExecState state = new NsExecState();
        exec(state, "csel \"Yes\",*yes,\"Option 2\",*opt2");
        exec(state, "cselbtn 0,144,0,0");
        exec(state, "cselbtn 1,145,0,0");
        exec(state, "selectbtnwait %1");
        assertEquals(java.util.Arrays.asList("Yes", "Option 2"), listener.lastChoices);
        assertEquals(java.util.Arrays.asList(144, 145), state.pendingChoiceButtonIds);
    }

    @Test
    public void cselbtnNoOpsForAnIndexWithNoDeclaredOption() {
        // In real ONScripter-EN, a "cselbtn" index past the
        // end of the "csel"-declared list (or before any "csel" was ever called at all) just returns
        // without registering a button -- not a fallback placeholder label.
        NsExecState state = new NsExecState();
        exec(state, "csel \"Only option\",*only");
        exec(state, "cselbtn 5,999,0,0"); // no option at index 5
        exec(state, "selectbtnwait %1");
        assertTrue(state.pendingChoiceButtonIds.isEmpty());
        assertEquals(VnEngine.State.WAITING_TAP, state.runState); // blocks anyway, see btnwaitHandler
    }

    @Test
    public void getversionReportsAVersionHighEnoughToPassARealMinimumVersionGate() {
        // Almost every professionally-packaged NScripter/ONScripter game opens its own "*start"
        // with "getversion %v:if %v>=192 jumpf" (or similar) as a self-check, falling through to a
        // "your interpreter is too old, get a newer one"+"end" if it fails -- a real, near-
        // universal idiom (e.g. Kagetsu Tohya's own "*start": "getversion %version:if
        // %version>=192 jumpf"). Before "getversion" was implemented, it silently no-op'd, so the
        // target variable kept its default value of 0 -- "0 >= 192" is always false, so EVERY real
        // game using this idiom hit its own "too old" error and quit on its very first line, before
        // a single frame of actual content ever ran. This reports 294 (matching real ONScripter-EN's
        // own NSC_VERSION), comfortably clearing any real minimum-version check.
        NsExecState state = new NsExecState();
        exec(state, "getversion %1");
        assertTrue(state.numVars.get(1) >= 192);
    }

    @Test
    public void rndSetsVariableToARandomValueInZeroToMaxMinusOne() {
        // Real ONScripter-EN's own rndCommand: "rnd var,max" -> var in [0, max-1] -- a real,
        // common use is picking one of several random flavor-text/encounter variants (e.g. Kagetsu
        // Tohya's own daily "horoscope": "rnd %msgno,218" picks 1 of 218 messages, paired with
        // "if %msgno==%lastmsgno0 skip -1" retrying if it repeats one of the last few shown).
        // Before "rnd" was implemented, it silently no-op'd -- the target variable was simply never
        // written, so a real anti-repeat retry loop like that compared the SAME never-changing
        // default (0) against itself forever: an infinite loop that never produced a message.
        NsExecState state = new NsExecState();
        for (int i = 0; i < 50; i++) {
            exec(state, "rnd %1,5");
            long v = state.numVars.get(1);
            assertTrue("expected 0<=v<5, got " + v, v >= 0 && v < 5);
        }
    }

    @Test
    public void rnd2SetsVariableToARandomValueInAnExplicitInclusiveRange() {
        NsExecState state = new NsExecState();
        for (int i = 0; i < 50; i++) {
            exec(state, "rnd2 %1,10,12");
            long v = state.numVars.get(1);
            assertTrue("expected 10<=v<=12, got " + v, v >= 10 && v <= 12);
        }
    }

    @Test
    public void getcselnumReturnsTheDeclaredOptionCount() {
        NsExecState state = new NsExecState();
        exec(state, "csel \"A\",*a,\"B\",*b,\"C\",*c");
        exec(state, "getcselnum %1");
        assertEquals(Long.valueOf(3), state.numVars.get(1));
    }

    @Test
    public void cselgotoJumpsToTheIndexedOptionsLabelAndClearsTheList() {
        NsExecState state = new NsExecState();
        state.labelIndex.put("right", 42);
        exec(state, "csel \"Go left\",*left,\"Go right\",*right");
        exec(state, "cselgoto 1");
        assertEquals(42, state.pc);
        assertTrue(state.customSelectTexts.isEmpty());
        assertTrue(state.customSelectLabels.isEmpty());
    }

    @Test
    public void cselgotoWithAnOutOfRangeIndexIsANoOp() {
        NsExecState state = new NsExecState();
        exec(state, "csel \"Only\",*only");
        exec(state, "cselgoto 5"); // no such option
        assertEquals(0, state.pc); // untouched: never jumped
        assertEquals(1, state.customSelectTexts.size()); // untouched: never cleared either
    }

    @Test
    public void colonInPlainDialogueDoesNotSplitIntoTwoLines() {
        NsExecState state = new NsExecState();
        exec(state, "Alice: \"Where are you going?\"");
        assertEquals(1, listener.textLines.size());
        assertEquals("Alice: \"Where are you going?\"", listener.textLines.get(0));
    }

    @Test
    public void colonInAllCapsTimeLikeTextDoesNotSplit() {
        NsExecState state = new NsExecState();
        exec(state, "3:00 PM");
        assertEquals(1, listener.textLines.size());
        assertEquals("3:00 PM", listener.textLines.get(0));
    }
}
