package com.eggman.backstage.privileged;

// Runs inside a Shizuku UserService (uid shell or root). Kept tiny on purpose:
// everything else goes through binder wrappers from the app process.
interface IShellService {
    void destroy() = 16777114;
    // Returns "<exitCode>\n<stdout>\n\u0000<stderr>"
    String exec(in String[] argv, long timeoutMs) = 1;
}
