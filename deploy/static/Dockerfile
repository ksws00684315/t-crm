# Static front-end artifact image (ADR-037 decision 7). Build context is the output directory of
# deploy/scripts/build-static.sh (admin/, kol/ with their licenses/ directories):
#   TCRM_STATIC_DIR=/tmp/static deploy/scripts/build-static.sh
#   docker build -f deploy/static/Dockerfile -t <registry>/crm-static:<version> /tmp/static
# The image is never run. release.sh extracts it (docker create + docker cp) into
# <TCRM_STATIC_DIR>/releases/<version>/. The CMD only exists because "docker create" needs a command.
FROM scratch
COPY admin /admin
COPY kol /kol
CMD ["/unused"]
