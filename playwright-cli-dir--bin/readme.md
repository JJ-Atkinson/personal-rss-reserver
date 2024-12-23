This file is here because playwright is Stupid, with a capital S. The stupidity explained:

- Playwright (mvn/java version) depends on the playwright runtime (python/node based) to exist
- Playwright driver requires chrome to exist (chrome specifically since it is driver free - I can add chrome std to the build)
- In nix, it's challenging to package, since the directory structure differs. 
  - [Playwright reference code to see access to the driver files][playwright-java-driver-access]
  - The system property `playwright.cli.dir` (##PlaywrightCliDir) is expected to contain the file `package/cli.js`. The nix packaged version [(see here)][nix-playwright-driver] puts the `cli.js` file top level.
- The solution is to build our own playwright driver location within this repo, and symlink files over dynamically. 
- Currently, only `cli.js` is required to be cross-referenced, BUT, in the future, THIS MAY CHANGE. Be ready to update stuff based on the nix packaging or based on what the java driver tries to access.


[playwright-java-driver-access]: https://github.com/microsoft/playwright-java/blob/42d0203b4949ba609250c3ca3e913fa587eb63d3/driver/src/main/java/com/microsoft/playwright/impl/driver/Driver.java#L70-L75
[nix-playwright-driver]: https://github.com/NixOS/nixpkgs/blob/nixos-24.11/pkgs/development/python-modules/playwright/default.nix#L70