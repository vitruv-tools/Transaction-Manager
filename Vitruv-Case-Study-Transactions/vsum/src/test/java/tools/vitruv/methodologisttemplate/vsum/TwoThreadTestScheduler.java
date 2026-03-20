package tools.vitruv.methodologisttemplate.vsum;

import tools.vitruv.change.atomic.EChange;
import tools.vitruv.change.atomic.eobject.CreateEObject;
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.composite.description.VitruviusChange;
import tools.vitruv.framework.vsum.schedule.Schedule;
import tools.vitruv.framework.vsum.schedule.Scheduler;
import tools.vitruv.methodologisttemplate.model.model.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TwoThreadTestScheduler implements Scheduler {
    private final List<VitruviusChange<Uuid>> changes = new ArrayList<>();
    /**
     * @param change
     * @return
     */
    @Override
    public boolean add(VitruviusChange<Uuid> change) {
        return changes.add(change);
    }

    @Override
    public Schedule end() {
        return new Schedule(Map.of(1, changes.stream().filter(it -> containsX(it, "Component")).toList(), 2, changes.stream().filter(it -> containsX(it, "Protocol")).toList()), changes.stream().map(Objects::hashCode).toList());
    }

    private static boolean containsX(VitruviusChange<Uuid> changes, String X) {
        for (EChange<Uuid> change : changes.getEChanges()) {
            if (change instanceof CreateEObject<Uuid>) {
                if (((CreateEObject<Uuid>) change).getAffectedEObjectType().getName().equals(X)) {
                    return true;
                }
            }
        }
        return false;
    }
}
