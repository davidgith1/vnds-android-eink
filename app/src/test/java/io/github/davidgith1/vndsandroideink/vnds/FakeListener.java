package io.github.davidgith1.vndsandroideink.vnds;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Records every callback for assertions, instead of driving real UI/audio like ReaderActivity does. */
final class FakeListener implements VnEngine.Listener {
    final List<String> textLines = new ArrayList<>();
    final List<String> speakers = new ArrayList<>();
    int textClears = 0;
    File lastBackground;
    final List<int[]> spriteLayersXY = new ArrayList<>(); // {layer, x, y}
    final List<File> spriteFiles = new ArrayList<>();
    File lastSound;
    int lastSoundTimes;
    File lastMusic;
    List<String> lastChoices;
    int lastDelayFrames = -1;
    final Map<String, String> globals = new HashMap<>();
    boolean finished = false;

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
        // VNDS never calls this.
    }

    @Override
    public void onTextClear() {
        textClears++;
    }

    @Override
    public void onBackground(File imageFile, int fadeFrames, VnEngine.SpriteTransparency transparency, int alphaMaskCells) {
        lastBackground = imageFile;
    }

    @Override
    public void onSprite(int layer, int x, int y, File imageFile, VnEngine.SpriteTransparency transparency, int alphaMaskCells) {
        spriteLayersXY.add(new int[]{layer, x, y});
        spriteFiles.add(imageFile);
    }

    @Override
    public void onSpriteCleared(int layer) {
        // VNDS never calls this.
    }

    @Override
    public void onSound(File soundFileOrNull, int times) {
        lastSound = soundFileOrNull;
        lastSoundTimes = times;
    }

    @Override
    public void onMusic(File musicFileOrNull) {
        lastMusic = musicFileOrNull;
    }

    @Override
    public void onChoices(List<String> options) {
        lastChoices = options;
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
        // VNDS never calls this.
    }

    @Override
    public void onLoadMenuRequested() {
        // VNDS never calls this.
    }
}
