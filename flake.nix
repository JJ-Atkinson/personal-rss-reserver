{
  description = "A clj-nix flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    clj-nix.url = "github:jlesquembre/clj-nix";
  };

  outputs = { self, nixpkgs, flake-utils, clj-nix }:

    flake-utils.lib.eachDefaultSystem (system:

      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ clj-nix.overlays.default ];
          config.allowUnfree = true;
        };
        
        runtimeJDK = pkgs.openjdk17;

        runtimeDeps = with pkgs; [
          google-chrome
          nodejs
          playwright-driver
          zprint

          openai-whisper-cpp
          ffmpeg
          awscli2

          which # Suprising, but I need this to make my life easier in the ./bin/env-vars file sourced.
        ];

        # References self! Interestingly, nix will iterate a function that contains
        # self until it reaches a fixed point or crashes out because of un-resolvable dependency conflicts.
        launch-rss-server = pkgs.writeShellScriptBin "launch-rss-server" ''
          # Call hello with a traditional greeting 

          PATH=${nixpkgs.lib.makeBinPath runtimeDeps}:${self.packages.${system}.binDerivation}/bin
          export PATH

          ${builtins.readFile ./bin/env-vars}
          
          # ##FlakeOverridePlaywrightCLI
          export PLAYWRIGHT_CLI_LOCATION="${self.packages.${system}.binDerivation}/bin"

          exec ${
            self.packages.${system}.baseCljDerivation
          }/bin/personal-rss-reserver
        '';
      in {
        formatter = nixpkgs.legacyPackages.x86_64-linux.nixfmt;

        devShell = pkgs.mkShell {
          buildInputs = [
            pkgs.clojure
            runtimeJDK
            pkgs.maven

            pkgs.babashka
            pkgs.clj-kondo
            pkgs.clojure-lsp
            pkgs.jet
          ] ++ runtimeDeps;
        };

        packages = {

          baseCljDerivation = 
          let 
            groupId = "dev.freeformsoftware";
            artifactId = "personal-rss-reserver";
            fullId = "${groupId}/${artifactId}";
            version = "1.0";
            main-ns = "personal-rss-feed.prod";
          in
            pkgs.mkCljBin {
              # pkgs = nixpkgs.legacyPackages.${system};
              nativeBuildInputs = [pkgs.git];

              projectSrc = ./.;
              jdkRunner = runtimeJDK;
              name = fullId;
              version = version;
              main-ns = main-ns;
              java-opts = [
                "--add-opens"
                "java.base/java.nio=ALL-UNNAMED" # ##SeeDepsEDN
                "--add-opens"
                "java.base/sun.nio.ch=ALL-UNNAMED"
                "-Djdk.httpclient.allowRestrictedHeaders=host"
              ];
              buildCommand = ''
                GIT_REF="${self.rev}"
                export GIT_REF
                clj -A:build -X build-prod/uber! :lib-name '${fullId}' :version '${version}' :main-ns '${main-ns}'
              '';
              # :lib-name :version :main-ns :compile-clj-opts :javac-opts
              # Default build command, slightly munged.
              #         ''
              #          clj-builder uber "${fullId}" "${version}" "${main-ns}" \
              #            '${builtins.toJSON compileCljOpts}' \
              #            '${builtins.toJSON javacOpts}'
              #        ''

              # nativeImage.enable = true;
              # customJdk.enable = true;
            };
          
          binDerivation = pkgs.stdenv.mkDerivation {
            name = "dev.freeformsoftware/personal-rss-reserver-bin-ext";
            src = ./bin;
            
            installPhase =
              ''
                mkdir -p $out/bin
                cp ./* $out/bin
              '';
          };

          default = pkgs.stdenv.mkDerivation {
            name = "dev.freeformsoftware/personal-rss-server-wrapped";
            nativeBuildInputs = runtimeDeps ++ [
              self.packages.${system}.baseCljDerivation
              self.packages.${system}.binDerivation
              launch-rss-server
            ];
            src = ./bin;

            installPhase =
              let baseCljDerPath = self.packages.${system}.baseCljDerivation;
              in ''
                mkdir -p $out/bin/extra-path
                cp ./* $out/bin/extra-path
                cp ${launch-rss-server}/bin/* $out/bin
              '';
          };
        };
      });
}
