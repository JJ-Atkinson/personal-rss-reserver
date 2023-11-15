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
      overlays = [
        clj-nix.overlays.default
      ];
    };
    in
    {
      devShell.${system} =
       with pkgs;
       mkShell {
         buildInputs = [
           # clojure
       # 
           # openjdk17
           # maven
       # 
           # babashka
           # clj-kondo
           # clojure-lsp
           # jet
       # 
           # google-chrome
           # nodejs
           # playwright-driver
       # 
           # openai-whisper-cpp
           # ffmpeg
         ];
       };
       
       
    });
}