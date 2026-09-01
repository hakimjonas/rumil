{
  description = "Arda Ecosystem - Scala 3 CI environment";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };

        # Scala Native dependencies
        scalaNativeDeps = with pkgs; [
          clang
          llvm
          zlib
          boehmgc
          libunwind
        ];

        # Common build tools
        buildTools = with pkgs; [
          sbt
          coursier
          git
          gnupg
        ];

        # Make a dev shell with specific JDK
        makeShell = jdk: pkgs.mkShell {
          buildInputs = [ jdk ] ++ buildTools ++ scalaNativeDeps;

          shellHook = ''
            export JAVA_HOME=${jdk}
          '';
        };

      in {
        devShells = {
          default = makeShell pkgs.temurin-bin-21;
          java21 = makeShell pkgs.temurin-bin-21;
          java25 = makeShell pkgs.temurin-bin-25;
        };
      }
    );
}
