{
  description = "A clj-nix flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    clj-nix.url = "github:jlesquembre/clj-nix";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      clj-nix,
    }:

    flake-utils.lib.eachDefaultSystem (
      system:

      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ clj-nix.overlays.default ];
          config.allowUnfree = true;
        };

        runtimeJDK = (pkgs.jdk23.override { enableJavaFX = true; });

        # Playwright setup following https://nixos.wiki/wiki/Playwright
        playwrightEnv = {
          PLAYWRIGHT_BROWSERS_PATH = "${pkgs.playwright-driver.browsers}";
          PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS = "true";
          PLAYWRIGHT_NODEJS_PATH = "${pkgs.nodejs}/bin/node";
          PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = "1";
          # Control debug output - comment/uncomment as needed
          # DEBUG = "pw:install";
          DEBUG = "pw:browser";
        };

        # Generate export statements from playwrightEnv for reuse in scripts and shells
        playwrightEnvExports = pkgs.lib.concatStringsSep "\n"
          (pkgs.lib.mapAttrsToList (name: value: "export ${name}=\"${value}\"") playwrightEnv);

        runtimeDeps = with pkgs; [
          nodejs
          playwright-driver
          zprint
          ffmpeg
          awscli2
          which
        ];

        launch-rss-server = pkgs.writeShellScriptBin "launch-rss-server" ''
          ${playwrightEnvExports}

          exec ${self.packages.${system}.baseCljDerivation}/bin/personal-rss-reserver
        '';
      in
      {
        formatter = nixpkgs.legacyPackages.x86_64-linux.nixfmt;

        devShell = pkgs.mkShell {
          shellHook = ''
            ${playwrightEnvExports}
          '';
          buildInputs = [
            pkgs.chromium
            (pkgs.clojure.override { jdk = runtimeJDK; })
            runtimeJDK
            pkgs.maven
            pkgs.babashka
            pkgs.clj-kondo
            pkgs.clojure-lsp
            pkgs.jet
            pkgs.claude-code
          ]
          ++ runtimeDeps;
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
              nativeBuildInputs = [ pkgs.git ];
              projectSrc = ./.;
              jdkRunner = runtimeJDK;
              name = fullId;
              version = version;
              main-ns = main-ns;
              java-opts = [
                "--add-opens"
                "java.base/java.nio=ALL-UNNAMED"
                "--add-opens"
                "java.base/sun.nio.ch=ALL-UNNAMED"
                "-Djdk.httpclient.allowRestrictedHeaders=host"
              ];
              buildCommand = ''
                GIT_REF="${self.rev or "dev"}"
                export GIT_REF
                clj -A:build -X build-prod/uber! :lib-name '${fullId}' :version '${version}' :main-ns '${main-ns}'
              '';
            };

          default = pkgs.stdenv.mkDerivation {
            name = "dev.freeformsoftware/personal-rss-server-wrapped";
            nativeBuildInputs = runtimeDeps ++ [
              self.packages.${system}.baseCljDerivation
              launch-rss-server
            ];
            src = ./bin;
            installPhase = ''
              mkdir -p $out/bin
              cp ${launch-rss-server}/bin/* $out/bin
            '';
          };
        };
      }
    );
}
