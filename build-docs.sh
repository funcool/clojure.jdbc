#!/bin/sh
VERSION="latest"

lein doc
(cd doc; make)

rm -rf /tmp/index.html /tmp/api

mv doc/index.html /tmp/index.html;
mv doc/api /tmp/api

git checkout gh-pages;

rm -rf ./index.html
rm -rf ./api

mv -fv /tmp/index.html ./
mv -fv /tmp/api ./

git add --all index.html
git add --all api
git commit -a -m "Update ${VERSION} doc"

