#! /bin/sh

set -e
set -x

cd rascal-lsp; 
mvn package
cd ..

cd rascal-vscode-extension
npm run lsp4j:package
cd ..

