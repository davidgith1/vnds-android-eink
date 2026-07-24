package io.github.davidgith1.vndsandroideink.nscripter;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;

import java.io.File;
import java.util.List;

/** One command's runtime behavior, registered against its mnemonic in {@link NsCommandDispatcher}. */
public interface NsCommandHandler {
    void handle(NsExecState state, List<NsArg> args, VnEngine.Listener listener, File vnDir);
}
