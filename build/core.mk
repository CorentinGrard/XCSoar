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
	$(SRC)/Renderer/% \
	$(SRC)/Gauge/% \
	$(SRC)/Menu/% \
	$(SRC)/Input/% \
	$(SRC)/Look/% \
	$(SRC)/UIUtil/% \
	$(SRC)/Screen/% \
	$(SRC)/ui/% \
	$(SRC)/CrossSection/% \
	$(SRC)/Monitor/% \
	$(SRC)/Weather/MapOverlay/% \
	$(SRC)/Weather/Rasp/RaspRenderer.cpp \
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
# the profile, so existing profiles load unchanged) and the layout
# scale used by the map renderer's look.
CORE_KEEP_SOURCES = \
	$(SRC)/Renderer/WaypointRendererSettings.cpp \
	$(SRC)/Renderer/AirspaceRendererSettings.cpp \
	$(SRC)/Gauge/VarioSettings.cpp \
	$(SRC)/Gauge/TrafficSettings.cpp \
	$(SRC)/Dialogs/DialogSettings.cpp \
	$(SRC)/Input/TaskEventObserver.cpp \
	$(SRC)/Screen/Layout.cpp \
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
	LIBMAPWINDOW LIBINFOBOX \
	WIDGET FORM DATA_FIELD

CORE_DEPENDS = \
	CORE_CANDIDATES \
	$(filter-out $(CORE_UI_DEPENDS),$(XCSOAR_DEPENDS))

CORE_LDLIBS =
ifeq ($(TARGET_IS_DARWIN),y)
CORE_LDLIBS += -framework CoreLocation
endif
ifeq ($(HAVE_HTTP),y)
CORE_LDLIBS += $(NETCDF_LDLIBS)
endif

CORE_HOST_SOURCES = \
	$(CORE_SRC_DIR)/host/CoreStartup.cpp \
	$(CORE_SRC_DIR)/host/Protection.cpp \
	$(CORE_SRC_DIR)/host/Seams.cpp \
	$(CORE_SRC_DIR)/host/CoreReceive.cpp

CORE_CPPFLAGS = -I$(CORE_SRC_DIR)/host

# Smoke test: start the core, run briefly, shut down.
CORE_SMOKE_SOURCES = \
	$(CORE_HOST_SOURCES) \
	$(CORE_SRC_DIR)/test/CoreSmoke.cpp
CORE_SMOKE_CPPFLAGS = $(CORE_CPPFLAGS)
CORE_SMOKE_DEPENDS = $(CORE_DEPENDS)
CORE_SMOKE_LDLIBS = $(CORE_LDLIBS)
$(eval $(call link-program,CoreSmoke,CORE_SMOKE))

.PHONY: core
core: $(CORE_SMOKE_BIN)
