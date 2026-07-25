package io.github.davidgith1.vndsandroideink.nscripter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;

public class NsCommandDispatcherTest {

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
    public void spbtnFallsBackToAGenericLabelForAnUnlabeledImageButton() {
        // Save/load/options menus commonly use "spbtn" calls to
        // register plain image-sprite buttons (no "lsp"-":s/…;…" text label at that layer) -- this
        // host can't render/hit-test the real graphics, so it still offers them as a native choice
        // rather than silently dropping the button and stranding the player.
        NsExecState state = new NsExecState();
        exec(state, "spbtn 103,103");
        exec(state, "btnwait %1");
        assertEquals(java.util.Collections.singletonList("Button 103"), listener.lastChoices);
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
    public void plainDialogueAutoContinuesWithoutWaiting() {
        NsExecState state = new NsExecState();
        state.runState = VnEngine.State.RUNNING;
        exec(state, "Just a caption");
        assertEquals("Just a caption", listener.textLines.get(0));
        assertEquals(VnEngine.State.RUNNING, state.runState); // untouched: no wait triggered
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
        state.pendingButtonLabels.add("Yes");
        state.pendingButtonIds.add(144);
        exec(state, "reset");
        assertEquals(7, state.pc);
        assertTrue(state.numVars.isEmpty());
        assertTrue(state.strVars.isEmpty());
        assertTrue(state.callStack.isEmpty());
        assertTrue(state.pendingButtonLabels.isEmpty());
        assertTrue(state.pendingButtonIds.isEmpty());
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
        exec(state, "spbtn 1,7");
        exec(state, "btnwait2 %1");
        assertEquals(VnEngine.State.WAITING_CHOICE, state.runState);
        assertEquals(java.util.Collections.singletonList(7), state.pendingChoiceButtonIds);

        NsExecState state2 = new NsExecState();
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
