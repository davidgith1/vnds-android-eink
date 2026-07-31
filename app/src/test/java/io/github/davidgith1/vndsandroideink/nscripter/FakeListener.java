package io.github.davidgith1.vndsandroideink.nscripter;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Records every callback for assertions, instead of driving real UI/audio like ReaderActivity does. */
final class FakeListener implements VnEngine.Listener {
    final List<String> textLines = new ArrayList<>();
    final List<String> textAppends = new ArrayList<>();
    final List<String> speakers = new ArrayList<>();
    int textClears = 0;
    File lastBackground;
    boolean lastBackgroundCleared;
    VnEngine.SpriteTransparency lastBackgroundTransparency;
    int lastBackgroundAlphaMaskCells;
    final List<int[]> spriteLayersXY = new ArrayList<>(); // {layer, x, y}
    final List<File> spriteFiles = new ArrayList<>();
    final List<VnEngine.SpriteTransparency> spriteTransparencies = new ArrayList<>();
    final List<Integer> spriteAlphaMaskCells = new ArrayList<>();
    final List<Integer> clearedLayers = new ArrayList<>();
    File lastSound;
    int lastSoundTimes;
    File lastMusic;
    boolean musicStopped;
    List<String> lastChoices;
    List<File> lastChoiceImages;
    List<VnEngine.SpriteTransparency> lastChoiceImageTransparencies;
    List<Integer> lastChoiceImageAlphaMaskCells;
    List<int[]> lastChoiceImageCropRects;
    int lastDelayFrames = -1;
    final Map<String, String> globals = new HashMap<>();
    boolean finished = false;
    boolean exitedToLibrary = false;
    boolean loadMenuRequested = false;

    @Override
    public void onSpeaker(String name) {
        speakers.add(name);
    }

    @Override
    public void onTextLine(String line) {
        textLines.add(line);
    }

    @Override
    public void onTextAppend(String moreText) {
        textAppends.add(moreText);
    }

    @Override
    public void onTextClear() {
        textClears++;
    }

    @Override
    public void onBackground(File imageFile, int fadeFrames, VnEngine.SpriteTransparency transparency, int alphaMaskCells) {
        lastBackground = imageFile;
        lastBackgroundCleared = imageFile == null;
        lastBackgroundTransparency = transparency;
        lastBackgroundAlphaMaskCells = alphaMaskCells;
    }

    @Override
    public void onSprite(int layer, int x, int y, File imageFile, VnEngine.SpriteTransparency transparency, int alphaMaskCells) {
        spriteLayersXY.add(new int[]{layer, x, y});
        spriteFiles.add(imageFile);
        spriteTransparencies.add(transparency);
        spriteAlphaMaskCells.add(alphaMaskCells);
    }

    @Override
    public void onSpriteCleared(int layer) {
        clearedLayers.add(layer);
    }

    @Override
    public void onSound(File soundFileOrNull, int times) {
        lastSound = soundFileOrNull;
        lastSoundTimes = times;
    }

    @Override
    public void onMusic(File musicFileOrNull) {
        lastMusic = musicFileOrNull;
        musicStopped = musicFileOrNull == null;
    }

    @Override
    public void onChoices(List<String> options) {
        lastChoices = options;
        lastChoiceImages = null;
    }

    @Override
    public void onChoices(List<String> options, List<File> images) {
        lastChoices = options;
        lastChoiceImages = images;
    }

    @Override
    public void onChoices(List<String> options, List<File> images,
                           List<VnEngine.SpriteTransparency> imageTransparencies, List<Integer> imageAlphaMaskCells) {
        lastChoices = options;
        lastChoiceImages = images;
        lastChoiceImageTransparencies = imageTransparencies;
        lastChoiceImageAlphaMaskCells = imageAlphaMaskCells;
    }

    @Override
    public void onChoices(List<String> options, List<File> images,
                           List<VnEngine.SpriteTransparency> imageTransparencies, List<Integer> imageAlphaMaskCells,
                           List<int[]> imageCropRects) {
        lastChoices = options;
        lastChoiceImages = images;
        lastChoiceImageTransparencies = imageTransparencies;
        lastChoiceImageAlphaMaskCells = imageAlphaMaskCells;
        lastChoiceImageCropRects = imageCropRects;
    }

    @Override
    public void onDelay(int frames) {
        lastDelayFrames = frames;
    }

    @Override
    public void onGlobalsChanged(Map<String, String> globals) {
        this.globals.clear();
        this.globals.putAll(globals);
    }

    @Override
    public void onFinished() {
        finished = true;
    }

    @Override
    public void onExitToLibrary() {
        exitedToLibrary = true;
    }

    @Override
    public void onLoadMenuRequested() {
        loadMenuRequested = true;
    }
}
