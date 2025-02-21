package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.trade.Trade
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.tilegroups.InfluenceTable
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import kotlin.math.floor
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/**
 * Creates the diplomacy screen for [viewingCiv].
 *
 * When [selectCiv] is given and [selectTrade] is not, that Civilization is selected as if clicked on the left side.
 * When [selectCiv] and [selectTrade] are supplied, that Trade for that Civilization is selected, used for the counter-offer option from `TradePopup`.
 * Note calling this with [selectCiv] a City State and [selectTrade] supplied is **not allowed**.
 */
class DiplomacyScreen(
    internal val viewingCiv: Civilization,
    private val selectCiv: Civilization? = null,
    private val selectTrade: Trade? = null
): BaseScreen(), RecreateOnResize {
    companion object {
        private const val nationIconSize = 100f
        private const val nationIconPad = 10f
    }

    private val leftSideTable = Table().apply { defaults().pad(nationIconPad) }
    private val leftSideScroll = ScrollPane(leftSideTable)
    internal val rightSideTable = Table()
    private val closeButton = Constants.close.toTextButton()

    internal fun isNotPlayersTurn() = !GUI.isAllowedChangeState()

    init {
        val splitPane = SplitPane(leftSideScroll, rightSideTable, false, skin)
        splitPane.splitAmount = 0.2f

        updateLeftSideTable(selectCiv)

        splitPane.setFillParent(true)
        stage.addActor(splitPane)

        closeButton.onActivation { UncivGame.Current.popScreen() }
        closeButton.keyShortcuts.add(KeyCharAndCode.BACK)
        closeButton.label.setFontSize(Constants.headingFontSize)
        closeButton.labelCell.pad(10f)
        closeButton.pack()
        positionCloseButton()
        stage.addActor(closeButton) // This must come after the split pane so it will be above, that the button will be clickable

        if (selectCiv != null) {
            if (selectTrade != null) {
                val tradeTable = setTrade(selectCiv)
                tradeTable.tradeLogic.currentTrade.set(selectTrade)
                tradeTable.offerColumnsTable.update()
            } else
                updateRightSide(selectCiv)
        }
    }

    private fun positionCloseButton() {
        closeButton.setPosition(stage.width * 0.1f, stage.height - 10f, Align.top)
    }

    internal fun updateLeftSideTable(selectCiv: Civilization?) {
        leftSideTable.clear()
        leftSideTable.add().padBottom(60f).row()  // room so the close button does not cover the first

        var selectCivY = 0f

        for (civ in viewingCiv.diplomacyFunctions.getKnownCivsSorted()) {
            if (civ == selectCiv) {
                selectCivY = leftSideTable.prefHeight
            }

            val civIndicator = ImageGetter.getNationPortrait(civ.nation, nationIconSize)

            val relationLevel = civ.getDiplomacyManager(viewingCiv).relationshipLevel()
            val relationshipIcon = if (civ.isCityState() && relationLevel == RelationshipLevel.Ally)
                ImageGetter.getImage("OtherIcons/Star")
                    .surroundWithCircle(size = 30f, color = relationLevel.color).apply {
                        actor.color = Color.GOLD
                    }
            else
                ImageGetter.getCircle().apply {
                    color = if (viewingCiv.isAtWarWith(civ)) Color.RED else relationLevel.color
                    setSize(30f, 30f)
                }
            civIndicator.addActor(relationshipIcon)

            if (civ.isCityState()) {
                val innerColor = civ.gameInfo.ruleset.nations[civ.civName]!!.getInnerColor()
                val typeIcon = ImageGetter.getImage("CityStateIcons/"+civ.cityStateType.name)
                    .surroundWithCircle(size = 35f, color = innerColor).apply {
                        actor.color = Color.BLACK
                    }
                civIndicator.addActor(typeIcon)
                typeIcon.y = floor(civIndicator.height - typeIcon.height)
                typeIcon.x = floor(civIndicator.width - typeIcon.width)
            }

            if (civ.isCityState() && civ.questManager.haveQuestsFor(viewingCiv)) {
                val questIcon = ImageGetter.getImage("OtherIcons/Quest")
                    .surroundWithCircle(size = 30f, color = Color.GOLDENROD)
                civIndicator.addActor(questIcon)
                questIcon.x = floor(civIndicator.width - questIcon.width)
            }

            val civNameLabel = civ.civName.toLabel(hideIcons = true)
            leftSideTable.add(civIndicator).row()
            leftSideTable.add(civNameLabel).padBottom(20f).row()

            civIndicator.onClick { updateRightSide(civ) }
            civNameLabel.onClick { updateRightSide(civ) }
        }

        if (selectCivY != 0f) {
            leftSideScroll.layout()
            leftSideScroll.scrollY = selectCivY + (nationIconSize + 2 * nationIconPad - stage.height) / 2
            leftSideScroll.updateVisualScroll()
        }
    }

    internal fun updateRightSide(otherCiv: Civilization) {
        rightSideTable.clear()
        UncivGame.Current.musicController.chooseTrack(otherCiv.civName,
            MusicMood.peaceOrWar(viewingCiv.isAtWarWith(otherCiv)),MusicTrackChooserFlags.setSelectNation)
        rightSideTable.add(ScrollPane(
            if (otherCiv.isCityState()) CityStateDiplomacyTable(this).getCityStateDiplomacyTable(otherCiv)
            else MajorCivDiplomacyTable(this).getMajorCivDiplomacyTable(otherCiv)
        )).height(stage.height)
    }

    //region City State Diplomacy

    //endregion
    //region Major Civ Diplomacy
    internal fun setTrade(civ: Civilization): TradeTable {
        rightSideTable.clear()
        val tradeTable = TradeTable(civ, this)
        rightSideTable.add(tradeTable)
        return tradeTable
    }

    internal fun getRelationshipTable(otherCivDiplomacyManager: DiplomacyManager): Table {
        val relationshipTable = Table()

        val opinionOfUs =
            if (otherCivDiplomacyManager.civInfo.isCityState()) otherCivDiplomacyManager.getInfluence().toInt()
            else otherCivDiplomacyManager.opinionOfOtherCiv().toInt()

        relationshipTable.add("{Our relationship}: ".toLabel())
        val relationshipLevel = otherCivDiplomacyManager.relationshipLevel()
        val relationshipText = relationshipLevel.name.tr() + " ($opinionOfUs)"
        val relationshipColor = when (relationshipLevel) {
            RelationshipLevel.Neutral -> Color.WHITE
            RelationshipLevel.Favorable, RelationshipLevel.Friend,
            RelationshipLevel.Ally -> Color.GREEN
            RelationshipLevel.Afraid -> Color.YELLOW
            else -> Color.RED
        }

        relationshipTable.add(relationshipText.toLabel(relationshipColor)).row()
        if (otherCivDiplomacyManager.civInfo.isCityState())
            relationshipTable.add(
                InfluenceTable(
                    otherCivDiplomacyManager.getInfluence(),
                    relationshipLevel,
                    200f, 10f
                )
            ).colspan(2).pad(5f)
        return relationshipTable
    }

    internal fun getDeclareWarButton(
        diplomacyManager: DiplomacyManager,
        otherCiv: Civilization
    ): TextButton {
        val declareWarButton = "Declare war".toTextButton(skin.get("negative", TextButton.TextButtonStyle::class.java))
        val turnsToPeaceTreaty = diplomacyManager.turnsToPeaceTreaty()
        if (turnsToPeaceTreaty > 0) {
            declareWarButton.disable()
            declareWarButton.setText(declareWarButton.text.toString() + " ($turnsToPeaceTreaty${Fonts.turn})")
        }
        declareWarButton.onClick {
            ConfirmPopup(this, getDeclareWarButtonText(otherCiv), "Declare war") {
                diplomacyManager.declareWar()
                setRightSideFlavorText(otherCiv, otherCiv.nation.attacked, "Very well.")
                updateLeftSideTable(otherCiv)
                UncivGame.Current.musicController.chooseTrack(otherCiv.civName, MusicMood.War, MusicTrackChooserFlags.setSpecific)
            }.open()
        }
        if (isNotPlayersTurn()) declareWarButton.disable()
        return declareWarButton
    }

    private fun getDeclareWarButtonText(otherCiv: Civilization): String {
        val messageLines = arrayListOf<String>()
        messageLines += "Declare war on [${otherCiv.civName}]?"
        // Tell the player who all will join the other side from defensive pacts
        val otherCivDefensivePactList = otherCiv.diplomacy.values.filter {
            otherCivDiploManager -> otherCivDiploManager.otherCiv() != viewingCiv
            && otherCivDiploManager.diplomaticStatus == DiplomaticStatus.DefensivePact
            && !otherCivDiploManager.otherCiv().isAtWarWith(viewingCiv) }
            .map { it.otherCiv() }.toMutableList()
        // Go through and find all of the defensive pact chains and add them to the list
        var listIndex = 0
        while (listIndex < otherCivDefensivePactList.size) {
            messageLines += if (viewingCiv.knows(otherCivDefensivePactList[listIndex]))
                "[${otherCivDefensivePactList[listIndex].civName}] will also join them in the war"
            else "An unknown civilization will also join them in the war"

            // Add their defensive pact allies
            otherCivDefensivePactList.addAll(otherCivDefensivePactList[listIndex].diplomacy.values
                .filter { diploChain -> diploChain.diplomaticStatus == DiplomaticStatus.DefensivePact
                    && !otherCivDefensivePactList.contains(diploChain.otherCiv())
                    && diploChain.otherCiv() != viewingCiv && diploChain.otherCiv() != otherCiv
                    && !diploChain.otherCiv().isAtWarWith(viewingCiv) }
                .map { it.otherCiv() })
            listIndex++
        }

        // Tell the player that their defensive pacts will be canceled.
        for (civDiploManager in viewingCiv.diplomacy.values) {
            if (civDiploManager.otherCiv() != otherCiv
                && civDiploManager.diplomaticStatus == DiplomaticStatus.DefensivePact
                && !otherCivDefensivePactList.contains(civDiploManager.otherCiv())) {
                messageLines += "This will cancel your defensive pact with [${civDiploManager.otherCivName}]"
            }
        }
        return messageLines.joinToString("\n") { "{$it}" }
    }

    //endregion

    // response currently always gets "Very Well.", but that may expand in the future.
    @Suppress("SameParameterValue")
    internal fun setRightSideFlavorText(
        otherCiv: Civilization,
        flavorText: String,
        response: String
    ) {
        val diplomacyTable = Table()
        diplomacyTable.defaults().pad(10f)
        diplomacyTable.add(LeaderIntroTable(otherCiv))
        diplomacyTable.addSeparator()
        diplomacyTable.add(flavorText.toLabel()).row()

        val responseButton = response.toTextButton()
        responseButton.onActivation { updateRightSide(otherCiv) }
        responseButton.keyShortcuts.add(KeyCharAndCode.SPACE)
        diplomacyTable.add(responseButton)

        rightSideTable.clear()
        rightSideTable.add(diplomacyTable)
    }

    internal fun getGoToOnMapButton(civilization: Civilization): TextButton {
        val goToOnMapButton = "Go to on map".toTextButton()
        goToOnMapButton.onClick {
            val worldScreen = UncivGame.Current.resetToWorldScreen()
            worldScreen.mapHolder.setCenterPosition(civilization.getCapital()!!.location, selectUnit = false)
        }
        return goToOnMapButton
    }

    /** Calculate a width for [TradeTable] two-column layout, called from [OfferColumnsTable]
     *
     *  _Caller is responsible to not exceed this **including its own padding**_
     */
    // Note breaking the rule above will squeeze the leftSideScroll to the left - cumulatively.
    internal fun getTradeColumnsWidth() = (stage.width * 0.8f - 3f) / 2  // 3 for SplitPane handle

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        positionCloseButton()
    }

    override fun recreate(): BaseScreen = DiplomacyScreen(viewingCiv, selectCiv, selectTrade)
}
