#!/bin/bash
set -ev
git clone https://github.com/$TRAVIS_REPO_SLUG.git $TRAVIS_REPO_SLUG
cd $TRAVIS_REPO_SLUG
git checkout -qf $TRAVIS_COMMIT
mvn -B release:prepare release:perform  -DskipTests=true --settings settings.xml