package io.github.keplersj.selkie.media

import android.media.session.PlaybackState
import io.github.keplersj.selkie.engine.Bind
import io.github.keplersj.selkie.engine.EngineEnv
import io.github.keplersj.selkie.engine.ShellSpec
import io.github.keplersj.selkie.proot.ProotCommandBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuestMediaTest {
    @get:Rule val tmp = TemporaryFolder()

    private val line = """{"active":"Lollypop","players":[{"name":"Lollypop","identity":"Lollypop","status":"Playing",""" +
        """"title":"Hayloft","artist":"Mother Mother","album":"O My Heart","art_url":"file:///home/user/.cache/cover.jpg",""" +
        """"length_us":180000000,"position_us":5000000,"track_id":"/t/1","can_play":true,"can_pause":true,""" +
        """"can_next":true,"can_previous":false,"can_seek":true,"future_field":1}]}"""

    @Test fun aLineFromDroidcMediaParses() {
        val s = GuestMediaSnapshot.parse(line)!!
        val p = s.current!!
        assertEquals("Hayloft", p.title)
        assertEquals("Mother Mother", p.artist)
        assertEquals(180_000_000L, p.lengthUs)
        assertTrue(p.canSeek && !p.canPrevious)
        assertNull(GuestMediaSnapshot.parse("media: waiting for the session bus"))
        assertNull(GuestMediaSnapshot.parse("""{"players":[]}""")!!.current)
    }

    @Test fun aPlayingPlayerMapsOntoTheMediaSession() {
        val p = GuestMediaSnapshot.parse(line)!!.current!!
        assertEquals(PlaybackState.STATE_PLAYING, MediaMapping.state(p))
        assertEquals(5_000L, MediaMapping.positionMs(p))
        assertEquals(1f, MediaMapping.speed(p))
        val a = MediaMapping.actions(p)
        for (want in listOf(PlaybackState.ACTION_PLAY_PAUSE, PlaybackState.ACTION_PAUSE,
                PlaybackState.ACTION_SKIP_TO_NEXT, PlaybackState.ACTION_SEEK_TO)) assertTrue(a and want != 0L)
        assertEquals(0L, a and PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        assertEquals("Mother Mother · O My Heart", MediaMapping.subtitle(p))
    }

    @Test fun aStoppedPlayerWithNothingToSayShowsItsName() {
        val p = GuestPlayer(name = "firefox.instance_1", identity = "Firefox")
        assertEquals(PlaybackState.STATE_STOPPED, MediaMapping.state(p))
        assertEquals(PlaybackState.PLAYBACK_POSITION_UNKNOWN, MediaMapping.positionMs(p))
        assertEquals(0f, MediaMapping.speed(p))
        assertEquals(0L, MediaMapping.actions(p) and PlaybackState.ACTION_PLAY_PAUSE)
        assertEquals("Firefox", MediaMapping.subtitle(p))
    }

    @Test fun commandsNameThePlayerAndRefuseOddNames() {
        assertEquals("play-pause Lollypop", mediaCommand("play-pause", "Lollypop"))
        assertEquals("seek-to vlc 1500000", mediaCommand("seek-to", "vlc", 1_500_000))
        assertNull(mediaCommand("next", "two words"))
        assertNull(mediaCommand("next", ""))
    }

    @Test fun onlyFileUrlsAreCovers() {
        assertEquals("/home/user/a b.png", MediaMapping.guestFilePath("file:///home/user/a%20b.png"))
        assertNull(MediaMapping.guestFilePath("https://example.org/a.png"))
        assertNull(MediaMapping.guestFilePath("file://otherhost/a.png"))
        assertNull(MediaMapping.guestFilePath("not a url"))
    }

    @Test fun mediaRunsUnderTheSupervisorOnTheSessionBus() {
        val env = EngineEnv(File("/lib"), File("/files"), File("/cache"), 10234, 10234, emptyList())
        val inv = ProotCommandBuilder(env, ShellSpec("/files/rootfs")).media()
        assertEquals(listOf("/usr/bin/droidc", "supervise", "--", "/usr/bin/droidc", "media", "--timeout",
            ProotCommandBuilder.AUDIO_OUT_WAIT_SECONDS.toString()), inv.argv.takeLast(7))
        assertEquals(ProotCommandBuilder.USER_SERVICES_WAIT_SECONDS.toString(), inv.env["DROIDC_USER_SERVICES_WAIT"])
    }

    @Test fun guestPathsFindTheirHostFilesAndStayInside() {
        val files = tmp.newFolder("files")
        val env = EngineEnv(File("/lib"), files, tmp.newFolder("cache"), 10234, 10234, emptyList())
        val rootfs = File(files, "rootfs").apply { mkdirs() }
        val home = tmp.newFolder("home")
        val b = ProotCommandBuilder(env, ShellSpec(rootfs.path, listOf(Bind(home.path, "/home/user"))))
        assertEquals(File(home, ".cache/cover.jpg").canonicalFile, b.hostPath("/home/user/.cache/cover.jpg"))
        assertEquals(File(env.guestTmp, "art.png").canonicalFile, b.hostPath("/tmp/art.png"))
        assertEquals(File(rootfs, "usr/share/icons/a.png").canonicalFile, b.hostPath("/usr/share/icons/a.png"))
        assertNull(b.hostPath("relative.png"))
        assertNull(b.hostPath("/home/user/../../etc/passwd"))
        assertNull(b.hostPath("/proc/self/environ"))
        val secret = tmp.newFile("secret.png")
        java.nio.file.Files.createSymbolicLink(File(home, "escape.png").toPath(), secret.toPath())
        assertNull(b.hostPath("/home/user/escape.png"))
    }
}
