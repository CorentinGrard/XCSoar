# XCSoar Mobile: the headless core (mobile/docs/ARCHITECTURE.md).
#
# The core is XCSoar's backend without its user interface.  It reuses
# the objects of the main program: CORE_CANDIDATES is every source of
# XCSOAR_SOURCES except the UI, packed into a static archive.  Programs
# linked against it only pull in what is reachable from their entry
# points, so any UI function still referenced by the backend shows up
# as an undefined symbol (a "seam", see mobile/docs/DECISIONS.md D5).

CORE_SRC_DIR = $(topdir)/core

# Main-program sources that belong to the user interface: windows,
# dialogs, widgets, input handling, and the glue that reacts to
# backend events by showing UI (monitors, settings-changed handlers).
CORE_UI_SOURCES = \
	$(SRC)/Dialogs/% \
	$(SRC)/Gauge/% \
	$(SRC)/Menu/% \
	$(SRC)/Input/% \
	$(SRC)/UIUtil/% \
	$(SRC)/ui/% \
	$(SRC)/CrossSection/% \
	$(SRC)/Monitor/% \
	$(SRC)/Weather/MapOverlay/% \
	$(SRC)/Hardware/Display% \
	$(SRC)/Hardware/RotateDisplay.cpp \
	$(SRC)/Apple/MacOSMainMenu.cpp \
	$(SRC)/Operation/VerboseOperationEnvironment.cpp \
	$(SRC)/XCSoar.cpp \
	$(SRC)/Startup.cpp \
	$(SRC)/MainWindow.cpp \
	$(SRC)/DrawThread.cpp \
	$(SRC)/ProcessTimer.cpp \
	$(SRC)/Protection.cpp \
	$(SRC)/Message.cpp \
	$(SRC)/PopupMessage.cpp \
	$(SRC)/StatusMessage.cpp \
	$(SRC)/PageActions.cpp \
	$(SRC)/PageOverlayTitle.cpp \
	$(SRC)/UIGlobals.cpp \
	$(SRC)/UIActions.cpp \
	$(SRC)/UIReceiveBlackboard.cpp \
	$(SRC)/UtilsSettings.cpp \
	$(SRC)/DataGlobals.cpp \
	$(SRC)/Progress%.cpp \
	$(SRC)/ActionInterfaceUI.cpp \
	$(SRC)/HorizonWidget.cpp \
	$(SRC)/FlarmProgressOverlay.cpp \
	$(SRC)/Pan.cpp \
	$(SRC)/TeamActions.cpp \
	$(SRC)/DisplayMode.cpp

# Kept although they live in UI directories: settings (the core owns
# the profile, so existing profiles load unchanged) and the display
# density used by the map (Renderer/ and Screen/ stay whole: the map
# draws with them, D6).
CORE_KEEP_SOURCES = \
	$(SRC)/Gauge/VarioSettings.cpp \
	$(SRC)/Gauge/TrafficSettings.cpp \
	$(SRC)/Dialogs/DialogSettings.cpp \
	$(SRC)/Input/TaskEventObserver.cpp \
	$(SRC)/Hardware/DisplayDPI.cpp

CORE_CANDIDATES_SOURCES = \
	$(filter-out $(CORE_UI_SOURCES),$(XCSOAR_SOURCES)) \
	$(filter $(CORE_KEEP_SOURCES),$(XCSOAR_SOURCES)) \
	$(SRC)/InfoBoxes/InfoBoxSettings.cpp

# No CORE_CANDIDATES_DEPENDS: the objects are shared with the main
# program, whose rule already sets their compile flags, and a DEPENDS
# list here would also be linked (dragging the UI libraries back in).
$(eval $(call link-library,core-candidates,CORE_CANDIDATES))

# The objects are usually older than the archive (the main program
# built them), so a changed source list alone would not rebuild it.
$(CORE_CANDIDATES_BIN): $(topdir)/build/core.mk

# Libraries the core links: the main program's minus the UI ones.
# LOOK and SCREEN stay: the map renderer (with its canvas) will be part
# of the core (D6); static archives only contribute what is referenced.
CORE_UI_DEPENDS = \
	LIBINFOBOX \
	FORM DATA_FIELD

CORE_DEPENDS = \
	CORE_CANDIDATES \
	$(filter-out $(CORE_UI_DEPENDS),$(XCSOAR_DEPENDS))

ifeq ($(TARGET),ANDROID)
# The screen library without its Android bitmap loader, which needs
# the old UI's NativeView; core/map/AndroidBitmap.cpp replaces it.
CORE_SCREEN_SOURCES = \
	$(filter-out $(CANVAS_SRC_DIR)/android/Bitmap.cpp,$(SCREEN_SOURCES))
CORE_SCREEN_CPPFLAGS = $(SCREEN_CPPFLAGS)
CORE_SCREEN_DEPENDS = $(SCREEN_DEPENDS)
$(eval $(call link-library,core-screen,CORE_SCREEN))
CORE_DEPENDS := $(patsubst SCREEN,CORE_SCREEN,$(CORE_DEPENDS))
endif

CORE_LDLIBS =
ifeq ($(TARGET_IS_DARWIN),y)
CORE_LDLIBS += -framework CoreLocation
endif
ifeq ($(HAVE_HTTP),y)
CORE_LDLIBS += $(NETCDF_LDLIBS)
endif

# The candidates archive and the libraries depend on each other in both
# directions.  macOS ld64 and lld search archives repeatedly; GNU ld
# reads them once, left to right, so group the whole link there.
# (core-link-group adds the opening flag to one program's link.)
ifneq ($(TARGET_IS_DARWIN),y)
CORE_LDLIBS += -Wl,--end-group
define core-link-group
$$($(1)_NOSTRIP): LDFLAGS += -Wl,--start-group
endef
else
core-link-group =
endif

CORE_HOST_SOURCES = \
	$(CORE_SRC_DIR)/host/CoreStartup.cpp \
	$(CORE_SRC_DIR)/host/CoreTask.cpp \
	$(CORE_SRC_DIR)/host/Protection.cpp \
	$(CORE_SRC_DIR)/host/Seams.cpp \
	$(CORE_SRC_DIR)/host/CoreReceive.cpp \
	$(CORE_SRC_DIR)/host/CoreEventLoop.cpp

ifeq ($(TARGET),ANDROID)
CORE_HOST_SOURCES += \
	$(CORE_SRC_DIR)/host/AndroidGlobals.cpp \
	$(CORE_SRC_DIR)/map/CoreMap.cpp \
	$(CORE_SRC_DIR)/map/MapSeams.cpp \
	$(CORE_SRC_DIR)/map/AndroidBitmap.cpp \
	$(SRC)/Renderer/BoxShadowRenderer.cpp
endif

CORE_API_SOURCES = \
	$(CORE_HOST_SOURCES) \
	$(CORE_SRC_DIR)/api/XcsoarCore.cpp

CORE_CPPFLAGS = -I$(CORE_SRC_DIR)/host -I$(CORE_SRC_DIR)/api -I$(CORE_SRC_DIR)/map

# XCSoar builds with -ffast-math, and GCC builds keep its
# -ffinite-math-only, which lets the compiler drop NaN checks.  The API
# must reject NaN coming from the app, whatever the compiler.
$(call SRC_TO_OBJ,$(CORE_SRC_DIR)/api/XcsoarCore.cpp): CXXFLAGS += -fno-finite-math-only
$(call SRC_TO_OBJ,$(CORE_SRC_DIR)/test/XcsReplay.cpp): CXXFLAGS += -fno-finite-math-only

.PHONY: core core-check core-drawables

ifeq ($(TARGET),ANDROID)

# The core as a shared library for the Android app (mobile/).
# --no-undefined: every unresolved symbol is a seam to fill.
LIBXCSOAR_CORE_SOURCES = $(CORE_API_SOURCES) $(CORE_SRC_DIR)/jni/CoreJni.cpp
LIBXCSOAR_CORE_CPPFLAGS = $(CORE_CPPFLAGS)
LIBXCSOAR_CORE_DEPENDS = $(CORE_DEPENDS)
LIBXCSOAR_CORE_LDLIBS = $(CORE_LDLIBS) -Wl,--no-undefined \
	-Wl,--version-script=$(CORE_SRC_DIR)/api/libxcsoar_core.map
LIBXCSOAR_CORE_STRIP = y
$(eval $(call link-shared-library,xcsoar_core,LIBXCSOAR_CORE))
$(eval $(call core-link-group,LIBXCSOAR_CORE))

core: $(LIBXCSOAR_CORE_BIN)

# The map's icons as Android drawables (packaged by mobile/app)
core-drawables: $(PNG_FILES)

else

# Smoke test: start the core, run briefly, shut down.
CORE_SMOKE_SOURCES = \
	$(CORE_HOST_SOURCES) \
	$(CORE_SRC_DIR)/test/CoreSmoke.cpp
CORE_SMOKE_CPPFLAGS = $(CORE_CPPFLAGS)
CORE_SMOKE_DEPENDS = $(CORE_DEPENDS)
CORE_SMOKE_LDLIBS = $(CORE_LDLIBS)
$(eval $(call link-program,CoreSmoke,CORE_SMOKE))
$(eval $(call core-link-group,CORE_SMOKE))

# L1: contract tests of the C API (TAP).  Headless flavour only:
#   make VFB=y core-check
TEST_CORE_API_SOURCES = \
	$(CORE_API_SOURCES) \
	$(CORE_SRC_DIR)/test/TestCoreApi.cpp \
	$(TEST_SRC_DIR)/tap.c
TEST_CORE_API_CPPFLAGS = $(CORE_CPPFLAGS) -I$(TEST_SRC_DIR)
TEST_CORE_API_DEPENDS = $(CORE_DEPENDS)
TEST_CORE_API_LDLIBS = $(CORE_LDLIBS)
$(eval $(call link-program,TestCoreApi,TEST_CORE_API))
$(eval $(call core-link-group,TEST_CORE_API))

# xcs-replay: deterministic replay to JSON lines (L2 golden tests)
XCS_REPLAY_SOURCES = \
	$(CORE_API_SOURCES) \
	$(CORE_SRC_DIR)/test/XcsReplay.cpp
XCS_REPLAY_CPPFLAGS = $(CORE_CPPFLAGS)
XCS_REPLAY_DEPENDS = $(CORE_DEPENDS)
XCS_REPLAY_LDLIBS = $(CORE_LDLIBS)
$(eval $(call link-program,xcs-replay,XCS_REPLAY))
$(eval $(call core-link-group,XCS_REPLAY))

CORE_TESTS = $(TEST_CORE_API_BIN)

core: $(CORE_SMOKE_BIN) $(CORE_TESTS) $(XCS_REPLAY_BIN)

# L1 (TAP contract tests) and L2 (golden replay, core/test/golden)
core-check: $(CORE_TESTS) $(XCS_REPLAY_BIN) | $(OUT)/test/dirstamp
	@$(NQ)echo "  CHECK   core"
	$(Q)$(PERL) $(TEST_SRC_DIR)/testall.pl $(CORE_TESTS)
	$(Q)python3 $(CORE_SRC_DIR)/test/check_golden.py --replay $(XCS_REPLAY_BIN)

endif

# The top-level Makefile only reads the dependency files of objects
# under src/; without this, header changes (e.g. core/api/xcsoar_core.h)
# would not rebuild the core's objects.
-include $(wildcard $(ABI_OUTPUT_DIR)/core/*/*.d)
