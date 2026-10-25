package io.github.jnesew.plainpoddroid.core.episode;

import java.io.IOException;

/** Local episode actions whose caller-controlled input is only an opaque media ID. */
public interface EpisodeActionStore {
    EpisodeDetails findDetails(String mediaId) throws IOException;

    EpisodeStateMutation setPlayed(String mediaId, boolean played)
            throws EpisodeActionException;

    EpisodeStateMutation setFavorite(String mediaId, boolean favorite)
            throws EpisodeActionException;
}
