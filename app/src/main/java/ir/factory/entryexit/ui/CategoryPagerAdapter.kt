package ir.factory.entryexit.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.ui.fragments.CategoryFragment
import ir.factory.entryexit.ui.fragments.InspectionListFragment

/** Backs the 5 tabs (Personnel, Machinery, Visitors, Drivers, Weekly Inspection) in
 *  MainActivity's ViewPager2. The 5th tab isn't a [PersonType] — it's a separate checklist
 *  view layered on top of the MACHINERY roster — so it's appended after the PersonType-driven
 *  tabs rather than folded into that enum. */
class CategoryPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private val tabs = listOf(
        PersonType.PERSONNEL,
        PersonType.MACHINERY,
        PersonType.VISITOR,
        PersonType.DRIVER
    )

    override fun getItemCount(): Int = tabs.size + 1 // +1 for the inspection tab

    override fun createFragment(position: Int): Fragment =
        if (position < tabs.size) CategoryFragment.newInstance(tabs[position]) else InspectionListFragment()

    fun typeAt(position: Int): PersonType? = tabs.getOrNull(position)

    fun positionOf(type: PersonType): Int = tabs.indexOf(type)
}
