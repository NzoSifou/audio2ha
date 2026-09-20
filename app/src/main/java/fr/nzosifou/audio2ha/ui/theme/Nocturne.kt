package fr.nzosifou.audio2ha.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design system « Nocturne » de la maquette Audio2HA (piste 1b).
 *
 * La maquette est dessinée sur un cadre de 1920 × 1080 px, soit exactement la
 * définition de la TV. Celle-ci étant en 320 dpi (facteur 2), **1 px de maquette
 * vaut 0,5 dp** : toutes les valeurs ci-dessous sont converties dans ce rapport.
 */
object Nocturne {

    // ------------------------------------------------------------------ couleurs

    /** Fond général. */
    val Bg = Color(0xFF161826)

    /** Menu latéral. */
    val Rail = Color(0xFF1B1D2C)

    /** Cartes et lignes de réglage. */
    val Card = Color(0xFF232532)

    /** Accent « blurple » : trait, libellés de rubrique, éléments actifs. */
    val Accent = Color(0xFF9184D9)

    /** Accent clair, pour les valeurs mises en avant. */
    val AccentSoft = Color(0xFFD2CEFD)

    val Text = Color(0xFFE9E9ED)
    val TextSecondary = Color(0xFFB2B6CA)
    val TextMuted = Color(0xFF75798C)
    val TextDim = Color(0xFF595D6C)

    /** Erreurs et actions destructrices. */
    val Danger = Color(0xFFD98A8A)

    /** Contour discret des boutons secondaires (blanc à 16 %). */
    val Outline = Color(0x29E9E9ED)

    // -------------------------------------------------------------- dimensions

    /** Menu latéral : 300 px. */
    val RailWidth = 150.dp
    val RailPaddingV = 17.dp
    val RailPaddingH = 14.dp

    /** Marges du contenu : 80 px sur les côtés, 72 px en haut. */
    val ContentPaddingH = 40.dp
    val ContentPaddingTop = 36.dp
    val ContentPaddingBottom = 32.dp

    val Radius = 4.dp
    val RadiusLarge = 7.dp

    /** Hauteur des cibles : 64 px et 56 px dans la maquette. */
    val ButtonHeight = 32.dp
    val ButtonHeightSmall = 28.dp

    /** Largeur fixe de la colonne des libellés dans les réglages (340 px). */
    val SettingLabelWidth = 170.dp

    val CardPadding = 18.dp
    val RowPaddingV = 13.dp
    val RowPaddingH = 15.dp

    val FocusRing = 1.5.dp

    // -------------------------------------------------------------- typographie

    /** Bandeau de rubrique : 12 px, majuscules, interlettrage large. */
    val LabelSize = 6.sp
    val LabelTracking = 0.3.sp

    /** État géant de l'accueil : 104 px. */
    val HeroSize = 52.sp

    /** Titre de page : 42 px. */
    val TitleSize = 21.sp

    /** Valeur d'un réglage : 22 px. */
    val ValueSize = 11.sp

    /** Libellé d'un réglage : 19 px. */
    val RowLabelSize = 9.5.sp

    /** Aide sous un libellé : 15 px. */
    val HintSize = 7.5.sp

    /** Texte courant : 18 px. */
    val BodySize = 9.sp

    /** Texte secondaire : 16 px. */
    val SmallSize = 8.sp
}
