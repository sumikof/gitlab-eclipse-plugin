.PHONY: sync-dependencies, clean-dependencies, e2e-tests

clean-dependencies:
	rm p2-libs/*.jar && \
 	rm p2-libs/.checksum.md5

sync-dependencies:
	./sync_dependencies.sh

e2e-tests:
	mvn verify -X
